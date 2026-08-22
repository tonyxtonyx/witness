package com.acme.semantic.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.cache.CacheKind;
import com.acme.semantic.cache.InProcessSemanticResultCache;
import com.acme.semantic.cache.SemanticCacheManager;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.AstSemanticSqlCompiler;
import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticQueryServiceCacheTest {
  @Test
  void authorizationFingerprintUsesEffectiveRenderedPolicyNotPrincipalIdentity() {
    MutableCountryPolicy policy = new MutableCountryPolicy();
    Fixture fixture = fixture(policy);
    SemanticPrincipal alice = SemanticPrincipal.authenticated("alice");
    SemanticPrincipal bob = SemanticPrincipal.authenticated("bob");
    SemanticPrincipal carol = SemanticPrincipal.authenticated("carol");
    policy.country(alice, "FI");
    policy.country(bob, "SE");
    policy.country(carol, "FI");

    SemanticQueryService.MetricQueryResponse aliceFi =
        fixture.service().query(alice, metricQuery(), "trace-alice-fi");
    policy.country(alice, "SE");
    SemanticQueryService.MetricQueryResponse aliceSe =
        fixture.service().query(alice, metricQuery(), "trace-alice-se");
    SemanticQueryService.MetricQueryResponse bobSe =
        fixture.service().query(bob, metricQuery(), "trace-bob-se");
    SemanticQueryService.MetricQueryResponse carolFi =
        fixture.service().query(carol, metricQuery(), "trace-carol-fi");

    assertThat(aliceFi.execution().cacheHit()).isFalse();
    assertThat(aliceSe.execution().cacheHit()).isFalse();
    assertThat(bobSe.execution().cacheHit()).isTrue();
    assertThat(carolFi.execution().cacheHit()).isTrue();
    assertThat(bobSe.execution().correlationId())
        .isNotEqualTo(aliceSe.execution().correlationId());
    verify(fixture.executor(), times(2)).execute(any(CompiledQuery.class), anyList());
    assertThat(fixture.backing().size(CacheKind.RESULT)).isEqualTo(2);
    assertThat(fixture.backing().size(CacheKind.PLAN)).isEqualTo(2);
  }

  @Test
  void repeatedDimensionValuesRequestExecutesOnlyOnceAndReportsTheHit() {
    MutableCountryPolicy policy = new MutableCountryPolicy();
    Fixture fixture = fixture(policy);
    SemanticPrincipal principal = SemanticPrincipal.authenticated("dimension-user");
    policy.country(principal, "FI");
    SemanticQueryService.DimensionValuesRequest request =
        new SemanticQueryService.DimensionValuesRequest(
            "retail.customers.country",
            List.of("retail.total_revenue"),
            "F",
            null,
            20,
            null);

    SemanticQueryService.DimensionValuesResponse first =
        fixture.service().dimensionValues(principal, request, "trace-first");
    SemanticQueryService.DimensionValuesResponse second =
        fixture.service().dimensionValues(principal, request, "trace-second");

    assertThat(first.values())
        .extracting(SemanticQueryService.DimensionValue::value)
        .containsExactly("FI");
    assertThat(first.execution().cacheHit()).isFalse();
    assertThat(second.execution().cacheHit()).isTrue();
    assertThat(first.execution().correlationId())
        .isNotEqualTo(second.execution().correlationId());
    verify(fixture.executor()).execute(any(CompiledQuery.class), anyList());
    assertThat(fixture.backing().size(CacheKind.DIMENSION_VALUES)).isEqualTo(1);
  }

  private Fixture fixture(MutableCountryPolicy policy) {
    SemanticModel model = TestModels.demo();
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    QueryExecutor executor = mock(QueryExecutor.class);
    when(executor.execute(any(CompiledQuery.class), anyList()))
        .thenAnswer(
            invocation -> {
              CompiledQuery compiled = invocation.getArgument(0);
              if (compiled.trinoSql().startsWith("SELECT DISTINCT")) {
                return new QueryResult(
                    List.of(new QueryResult.Column("country", Types.VARCHAR, "varchar", true)),
                    List.of(List.of("FI")),
                    "dimension-engine-query");
              }
              return new QueryResult(
                  List.of(new QueryResult.Column("revenue", Types.DECIMAL, "decimal", true)),
                  List.of(List.of(new BigDecimal("100.00"))),
                  "metric-engine-query");
            });
    SemanticProperties.Cache config =
        new SemanticProperties.Cache(
            true,
            100,
            4_000_000,
            new SemanticProperties.Layer(600),
            new SemanticProperties.Layer(300),
            new SemanticProperties.ResultLayer(30, 100, 1_000_000));
    SemanticProperties properties =
        new SemanticProperties(
            "semantic-model", "test", false, null, null, null, null, config);
    InProcessSemanticResultCache backing =
        new InProcessSemanticResultCache(config, new SimpleMeterRegistry(), Clock.systemUTC());
    SemanticCacheManager cache = new SemanticCacheManager(backing, config);
    SemanticQueryService service =
        new SemanticQueryService(
            catalog,
            policy,
            new AstSemanticSqlCompiler(),
            executor,
            properties,
            cache);
    return new Fixture(service, executor, backing);
  }

  private SemanticQuery metricQuery() {
    return new SemanticQuery(
        List.of("retail.total_revenue"), List.of(), null, List.of(), 10, "UTC");
  }

  private record Fixture(
      SemanticQueryService service,
      QueryExecutor executor,
      InProcessSemanticResultCache backing) {}

  private static final class MutableCountryPolicy implements SemanticAccessPolicy {
    private final Map<String, String> countries = new HashMap<>();

    private void country(SemanticPrincipal principal, String country) {
      countries.put(principal.id(), country);
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
      return principal != null && principal.authenticated();
    }

    @Override
    public boolean canReadMetric(
        SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
      return principal != null && principal.authenticated();
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
      return List.of(
          new SemanticQuery.FilterCondition(
              "retail.customers.country",
              SemanticQuery.FilterOperator.EQ,
              List.of(countries.get(principal.id()))));
    }
  }
}
