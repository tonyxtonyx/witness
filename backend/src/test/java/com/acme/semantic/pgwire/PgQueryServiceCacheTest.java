package com.acme.semantic.pgwire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.api.SemanticSqlReferenceAuthorizer;
import com.acme.semantic.cache.CacheKind;
import com.acme.semantic.cache.InProcessSemanticResultCache;
import com.acme.semantic.cache.ReadableModelCache;
import com.acme.semantic.cache.SemanticCacheManager;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.AstSemanticSqlCompiler;
import com.acme.semantic.compiler.SemanticSqlCompiler;
import com.acme.semantic.compiler.SqlCompilationException;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticErrorCode;
import com.acme.semantic.core.SemanticException;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.core.SemanticQuery;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PgQueryServiceCacheTest {
  @Test
  void principalsWithDifferentReadableModelsNeverShareCompiledPlans() {
    SemanticModel model = TestModels.demo();
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    ObjectVisibilityPolicy policy = new ObjectVisibilityPolicy();
    SemanticSqlCompiler compiler = spy(new AstSemanticSqlCompiler());
    SemanticProperties.Cache config = SemanticProperties.Cache.defaults();
    InProcessSemanticResultCache backing =
        new InProcessSemanticResultCache(
            config, new SimpleMeterRegistry(), Clock.systemUTC());
    ReadableModelCache readableModels = new ReadableModelCache(16);
    PgQueryService service =
        new PgQueryService(
            catalog,
            policy,
            mock(SemanticSqlReferenceAuthorizer.class),
            compiler,
            mock(QueryExecutor.class),
            new SemanticCacheManager(backing, config),
            readableModels);
    SemanticPrincipal visible = SemanticPrincipal.authenticated("visible");
    SemanticPrincipal hidden = SemanticPrincipal.authenticated("hidden");
    String sql = "SELECT status FROM retail.orders";

    PgQueryService.Prepared first = service.prepare(sql, session(visible));
    PgQueryService.Prepared cached = service.prepare(sql, session(visible));

    assertThat(first.compiled().correlationId())
        .isNotEqualTo(cached.compiled().correlationId());
    assertThatThrownBy(() -> service.prepare(sql, session(hidden)))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("Unknown semantic object");
    assertThatThrownBy(() -> service.prepare(sql, session(hidden)))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("Unknown semantic object");
    verify(compiler, times(3))
        .compile(org.mockito.ArgumentMatchers.eq(sql), org.mockito.ArgumentMatchers.any());
    assertThat(readableModels.modelBuildCount()).isEqualTo(2);
    assertThat(readableModels.fingerprintBuildCount()).isEqualTo(2);
  }

  @Test
  void dimensionVisibilitySeparatesCacheEntriesButEquivalentVisibilitySharesThem() {
    SemanticModel model = TestModels.demo();
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    DimensionVisibilityPolicy policy = new DimensionVisibilityPolicy();
    SemanticSqlCompiler compiler = spy(new AstSemanticSqlCompiler());
    QueryExecutor executor = mock(QueryExecutor.class);
    when(executor.execute(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            new QueryResult(
                List.of(new QueryResult.Column("customer_id", Types.BIGINT, "bigint", false)),
                List.of(List.of(101L)),
                "dimension-visibility-query"));
    SemanticProperties.Cache config = SemanticProperties.Cache.defaults();
    InProcessSemanticResultCache backing =
        new InProcessSemanticResultCache(
            config, new SimpleMeterRegistry(), Clock.systemUTC());
    ReadableModelCache readableModels = new ReadableModelCache(16);
    PgQueryService service =
        new PgQueryService(
            catalog,
            policy,
            mock(SemanticSqlReferenceAuthorizer.class),
            compiler,
            executor,
            new SemanticCacheManager(backing, config),
            readableModels);
    SemanticPrincipal alice = SemanticPrincipal.authenticated("alice-masked");
    SemanticPrincipal bob = SemanticPrincipal.authenticated("bob-unmasked");
    SemanticPrincipal carol = SemanticPrincipal.authenticated("carol-masked");
    String sql = "SELECT customer_id FROM retail.orders";

    QueryResult aliceResult =
        service.execute(service.prepare(sql, session(alice)), List.of(), session(alice));
    QueryResult bobResult =
        service.execute(service.prepare(sql, session(bob)), List.of(), session(bob));
    QueryResult carolResult =
        service.execute(service.prepare(sql, session(carol)), List.of(), session(carol));

    assertThat(aliceResult.cacheHit()).isFalse();
    assertThat(bobResult.cacheHit()).isFalse();
    assertThat(carolResult.cacheHit()).isTrue();
    assertThat(carolResult.correlationId()).isNotEqualTo(aliceResult.correlationId());
    assertThat(backing.size(CacheKind.PLAN)).isEqualTo(2);
    assertThat(backing.size(CacheKind.RESULT)).isEqualTo(2);
    assertThat(readableModels.modelBuildCount()).isEqualTo(3);
    assertThat(readableModels.fingerprintBuildCount()).isEqualTo(3);
    verify(compiler, times(2))
        .compile(org.mockito.ArgumentMatchers.eq(sql), org.mockito.ArgumentMatchers.any());
    verify(executor, times(2))
        .execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void readableModelAndFingerprintAreBuiltOnceAcrossPrepareAndExecuteMessages() {
    Fixture fixture = fixture(SemanticProperties.Cache.defaults());
    SemanticPrincipal principal = SemanticPrincipal.authenticated("visible");
    String sql = "SELECT status FROM retail.orders";

    PgQueryService.Prepared first = fixture.service().prepare(sql, session(principal));
    fixture.service().execute(first, List.of(), session(principal));
    PgQueryService.Prepared second = fixture.service().prepare(sql, session(principal));
    fixture.service().execute(second, List.of(), session(principal));

    assertThat(fixture.readableModels().modelBuildCount()).isEqualTo(1);
    assertThat(fixture.readableModels().fingerprintBuildCount()).isEqualTo(1);
    verify(fixture.compiler()).compile(
        org.mockito.ArgumentMatchers.eq(sql), org.mockito.ArgumentMatchers.any());
    verify(fixture.executor()).execute(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void globalDisableSkipsAuthorizationFingerprintAndEveryGovernedCacheKey() {
    SemanticProperties.Cache disabled =
        new SemanticProperties.Cache(
            false,
            100,
            4_000_000,
            new SemanticProperties.Layer(600),
            new SemanticProperties.Layer(300),
            new SemanticProperties.ResultLayer(30, 100, 1_000_000));
    Fixture fixture = fixture(disabled);
    SemanticPrincipal principal = SemanticPrincipal.authenticated("visible");
    String sql = "SELECT status FROM retail.orders";

    PgQueryService.Prepared first = fixture.service().prepare(sql, session(principal));
    fixture.service().execute(first, List.of(), session(principal));
    PgQueryService.Prepared second = fixture.service().prepare(sql, session(principal));
    fixture.service().execute(second, List.of(), session(principal));

    assertThat(fixture.readableModels().modelBuildCount()).isEqualTo(1);
    assertThat(fixture.readableModels().fingerprintBuildCount()).isZero();
    assertThat(fixture.policy().requiredFilterCalls()).isZero();
    assertThat(fixture.backing().size(CacheKind.PLAN)).isZero();
    assertThat(fixture.backing().size(CacheKind.RESULT)).isZero();
    verify(fixture.compiler(), times(2))
        .compile(org.mockito.ArgumentMatchers.eq(sql), org.mockito.ArgumentMatchers.any());
    verify(fixture.executor(), times(2))
        .execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
  }

  private Fixture fixture(SemanticProperties.Cache config) {
    SemanticModel model = TestModels.demo();
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    ObjectVisibilityPolicy policy = new ObjectVisibilityPolicy();
    SemanticSqlCompiler compiler = spy(new AstSemanticSqlCompiler());
    QueryExecutor executor = mock(QueryExecutor.class);
    when(executor.execute(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            new QueryResult(
                List.of(new QueryResult.Column("status", Types.VARCHAR, "varchar", true)),
                List.of(List.of("paid")),
                "pgwire-cache-query"));
    InProcessSemanticResultCache backing =
        new InProcessSemanticResultCache(
            config, new SimpleMeterRegistry(), Clock.systemUTC());
    ReadableModelCache readableModels = new ReadableModelCache(16);
    PgQueryService service =
        new PgQueryService(
            catalog,
            policy,
            mock(SemanticSqlReferenceAuthorizer.class),
            compiler,
            executor,
            new SemanticCacheManager(backing, config),
            readableModels);
    return new Fixture(
        service, compiler, executor, policy, backing, readableModels);
  }

  private PgQueryService.SessionSettings session(SemanticPrincipal principal) {
    return new PgQueryService.SessionSettings("retail", "retail", Map.of(), principal);
  }

  private record Fixture(
      PgQueryService service,
      SemanticSqlCompiler compiler,
      QueryExecutor executor,
      ObjectVisibilityPolicy policy,
      InProcessSemanticResultCache backing,
      ReadableModelCache readableModels) {}

  private static final class ObjectVisibilityPolicy implements SemanticAccessPolicy {
    private final AtomicInteger requiredFilterCalls = new AtomicInteger();

    private int requiredFilterCalls() {
      return requiredFilterCalls.get();
    }
    @Override
    public void requireAuthenticated(SemanticPrincipal principal) {
      if (principal == null || !principal.authenticated()) {
        throw new SemanticException(SemanticErrorCode.ACCESS_DENIED, "Authentication required");
      }
    }

    @Override
    public boolean canReadObject(
        SemanticPrincipal principal,
        SemanticModel model,
        SemanticModel.SemanticObject object) {
      return principal.id().equals("visible") || object.metadata().name().equals("customers");
    }

    @Override
    public boolean canReadMetric(
        SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
      return canReadObject(
          principal,
          model,
          model.resolveObject(metric.spec().baseObject(), model.domain(metric)).value());
    }

    @Override
    public boolean canViewCompiledSql(SemanticPrincipal principal) {
      return false;
    }

    @Override
    public boolean canViewPhysicalLineage(SemanticPrincipal principal) {
      return false;
    }

    @Override
    public List<SemanticQuery.FilterCondition> requiredFilters(
        SemanticPrincipal principal, SemanticModel model) {
      requiredFilterCalls.incrementAndGet();
      return List.of();
    }
  }

  private static final class DimensionVisibilityPolicy implements SemanticAccessPolicy {
    @Override
    public void requireAuthenticated(SemanticPrincipal principal) {
      if (principal == null || !principal.authenticated()) {
        throw new SemanticException(SemanticErrorCode.ACCESS_DENIED, "Authentication required");
      }
    }

    @Override
    public boolean canReadObject(
        SemanticPrincipal principal,
        SemanticModel model,
        SemanticModel.SemanticObject object) {
      return true;
    }

    @Override
    public boolean canReadMetric(
        SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
      return true;
    }

    @Override
    public boolean canReadDimension(
        SemanticPrincipal principal,
        SemanticModel model,
        SemanticModel.SemanticObject object,
        SemanticModel.Dimension dimension) {
      return !principal.id().endsWith("-masked") || !dimension.name().equals("status");
    }

    @Override
    public boolean canViewCompiledSql(SemanticPrincipal principal) {
      return false;
    }

    @Override
    public boolean canViewPhysicalLineage(SemanticPrincipal principal) {
      return false;
    }
  }
}
