package com.acme.semantic.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.compiler.SemanticSqlCompiler;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.execution.QueryExecutionException;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticCacheManagerTest {
  private static final CompiledQuery PLAN =
      new CompiledQuery(
          "SELECT value FROM governed_source",
          List.of(),
          List.of(new CompiledQuery.Column("value", "varchar")),
          "compiler-correlation");

  @Test
  void resultHitsHaveFreshCorrelationIdsAndAccurateMetadata() {
    Fixture fixture = fixture(cache(600, 300, 30, 100, 1_000_000));
    when(fixture.executor().execute(any(), anyList()))
        .thenReturn(result(List.of(List.of("FI")), "trino-1"));

    QueryResult first = fixture.execute("revision-1");
    QueryResult second = fixture.execute("revision-1");

    assertThat(first.cacheHit()).isFalse();
    assertThat(first.queryId()).isEqualTo("trino-1");
    assertThat(second.cacheHit()).isTrue();
    assertThat(second.queryId()).isNull();
    assertThat(first.correlationId()).isNotBlank().isNotEqualTo(second.correlationId());
    assertThat(second.rows()).containsExactly(List.of("FI"));
    verify(fixture.executor()).execute(any(), anyList());
    assertThat(fixture.registry().counter("semantic.cache.hits", "kind", "result").count())
        .isEqualTo(1);
    assertThat(fixture.registry().get("semantic.cache.size").tag("kind", "result").gauge().value())
        .isEqualTo(1);
  }

  @Test
  void planHitsMintFreshCorrelationIdsAndRespectAuthorization() {
    Fixture fixture = fixture(cache(600, 300, 30, 100, 1_000_000));
    SemanticSqlCompiler compiler = mock(SemanticSqlCompiler.class);
    var model = TestModels.demo();
    when(compiler.compile("SELECT value", model)).thenReturn(PLAN);

    CompiledQuery first = fixture.manager().compile("SELECT value", model, "scope-a", compiler);
    CompiledQuery second = fixture.manager().compile("SELECT value", model, "scope-a", compiler);
    CompiledQuery isolated = fixture.manager().compile("SELECT value", model, "scope-b", compiler);

    assertThat(first.correlationId()).isNotEqualTo(second.correlationId());
    assertThat(second.correlationId()).isNotEqualTo(isolated.correlationId());
    verify(compiler, times(2)).compile("SELECT value", model);
    assertThat(fixture.backing().size(CacheKind.PLAN)).isEqualTo(2);
  }

  @Test
  void oversizedResultsAreServedButNeverCached() {
    Fixture fixture = fixture(cache(600, 300, 30, 1, 1_000_000));
    List<List<Object>> rows = List.of(List.of("FI"), List.of("SE"));
    when(fixture.executor().execute(any(), anyList()))
        .thenReturn(result(rows, "trino-large"));

    QueryResult first = fixture.execute("revision-1");
    QueryResult second = fixture.execute("revision-1");

    assertThat(first.rows()).isEqualTo(rows);
    assertThat(second.rows()).isEqualTo(rows);
    assertThat(first.cacheHit()).isFalse();
    assertThat(second.cacheHit()).isFalse();
    verify(fixture.executor(), times(2)).execute(any(), anyList());
    assertThat(fixture.backing().size(CacheKind.RESULT)).isZero();
  }

  @Test
  void ttlExpiryCausesReexecution() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-22T00:00:00Z"));
    Fixture fixture = fixture(cache(600, 300, 2, 100, 1_000_000), clock);
    when(fixture.executor().execute(any(), anyList()))
        .thenReturn(result(List.of(List.of("FI")), "trino-ttl"));

    assertThat(fixture.execute("revision-1").cacheHit()).isFalse();
    clock.advanceSeconds(1);
    assertThat(fixture.execute("revision-1").cacheHit()).isTrue();
    clock.advanceSeconds(2);
    assertThat(fixture.execute("revision-1").cacheHit()).isFalse();

    verify(fixture.executor(), times(2)).execute(any(), anyList());
    assertThat(fixture.registry().counter("semantic.cache.evictions", "kind", "result").count())
        .isEqualTo(1);
  }

  @Test
  void zeroTtlAndGlobalDisableBypassCaching() {
    Fixture zero = fixture(cache(0, 0, 0, 100, 1_000_000));
    when(zero.executor().execute(any(), anyList()))
        .thenReturn(result(List.of(List.of("FI")), "trino-zero"));
    zero.execute("revision-1");
    zero.execute("revision-1");

    SemanticProperties.Cache disabled =
        new SemanticProperties.Cache(
            false,
            100,
            1_000_000,
            new SemanticProperties.Layer(60),
            new SemanticProperties.Layer(60),
            new SemanticProperties.ResultLayer(60, 100, 1_000_000));
    Fixture off = fixture(disabled);
    when(off.executor().execute(any(), anyList()))
        .thenReturn(result(List.of(List.of("FI")), "trino-off"));
    off.execute("revision-1");
    off.execute("revision-1");

    verify(zero.executor(), times(2)).execute(any(), anyList());
    verify(off.executor(), times(2)).execute(any(), anyList());
    assertThat(zero.backing().size(CacheKind.RESULT)).isZero();
    assertThat(off.backing().size(CacheKind.RESULT)).isZero();
  }

  @Test
  void revisionAndBoundValuesArePartOfTheResultKeyAndFailuresAreNotCached() {
    Fixture fixture = fixture(cache(600, 300, 30, 100, 1_000_000));
    when(fixture.executor().execute(any(), anyList()))
        .thenReturn(result(List.of(List.of("FI")), "one"))
        .thenThrow(new QueryExecutionException("XX000", "failure", null))
        .thenReturn(result(List.of(List.of("SE")), "three"));

    fixture.execute("revision-1");
    assertThatThrownBy(() -> fixture.execute("revision-2"))
        .isInstanceOf(QueryExecutionException.class);
    assertThat(fixture.execute("revision-2").rows()).containsExactly(List.of("SE"));

    verify(fixture.executor(), times(3)).execute(any(), anyList());
  }

  private Fixture fixture(SemanticProperties.Cache config) {
    return fixture(config, Clock.systemUTC());
  }

  private Fixture fixture(SemanticProperties.Cache config, Clock clock) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InProcessSemanticResultCache backing =
        new InProcessSemanticResultCache(config, registry, clock);
    QueryExecutor executor = mock(QueryExecutor.class);
    return new Fixture(new SemanticCacheManager(backing, config), backing, executor, registry);
  }

  private SemanticProperties.Cache cache(
      long planTtl, long dimensionTtl, long resultTtl, int resultRows, long resultBytes) {
    return new SemanticProperties.Cache(
        true,
        100,
        4_000_000,
        new SemanticProperties.Layer(planTtl),
        new SemanticProperties.Layer(dimensionTtl),
        new SemanticProperties.ResultLayer(resultTtl, resultRows, resultBytes));
  }

  private QueryResult result(List<List<Object>> rows, String queryId) {
    return new QueryResult(
        List.of(new QueryResult.Column("value", Types.VARCHAR, "varchar", true)),
        rows,
        queryId);
  }

  private record Fixture(
      SemanticCacheManager manager,
      InProcessSemanticResultCache backing,
      QueryExecutor executor,
      SimpleMeterRegistry registry) {
    private QueryResult execute(String revision) {
      return manager.execute(
          revision,
          PLAN,
          List.of(),
          SemanticCacheManager.emptyAuthorizationFingerprint(),
          executor);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
