package com.acme.semantic.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.AstSemanticSqlCompiler;
import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticQueryServiceTest {
  private final SemanticPrincipal principal = SemanticPrincipal.authenticated("test-key");
  private SemanticModel model;
  private QueryExecutor executor;
  private SemanticQueryService service;

  @BeforeEach
  void setUp() {
    model = TestModels.demo();
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    executor = mock(QueryExecutor.class);
    service =
        new SemanticQueryService(
            catalog,
            new AllowPolicy(),
            new AstSemanticSqlCompiler(),
            executor,
            new SemanticProperties("semantic-model", "test", null, null, null));
  }

  @Test
  void compilesCanonicalQueryWithoutExecutingData() {
    SemanticQuery query = governedQuery(100);

    SemanticQueryService.CompilationResponse response =
        service.compile(principal, query, "trace-compile");

    assertThat(response.valid()).isTrue();
    assertThat(response.compiledSql()).isNull();
    assertThat(response.plan().metrics()).containsExactly("retail.total_revenue");
    assertThat(response.plan().dimensions())
        .containsExactly("retail.orders.created_at", "retail.customers.country");
    assertThat(response.plan().joinPath()).containsExactly("order_customer");
    assertThat(response.warnings()).hasSize(1);
    verify(executor, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void reportsInvalidGranularityAndTypedFilterMismatch() {
    SemanticQuery invalidGranularity =
        new SemanticQuery(
            List.of("retail.total_revenue"),
            List.of(
                new SemanticQuery.DimensionSelection(
                    "retail.customers.country", SemanticQuery.TimeGranularity.MONTH)),
            null,
            List.of(),
            10,
            "UTC");
    SemanticQuery invalidFilter =
        new SemanticQuery(
            List.of("retail.total_revenue"),
            List.of(),
            new SemanticQuery.FilterGroup(
                SemanticQuery.LogicalOperator.AND,
                List.of(
                    new SemanticQuery.FilterCondition(
                        "retail.orders.amount",
                        SemanticQuery.FilterOperator.GT,
                        List.of("not-a-number")))),
            List.of(),
            10,
            "UTC");

    assertThat(service.compile(principal, invalidGranularity, "trace-1").errors())
        .extracting(SemanticQueryService.ValidationIssue::code)
        .containsExactly("INVALID_SEMANTIC_QUERY");
    assertThat(service.compile(principal, invalidFilter, "trace-2").errors())
        .extracting(SemanticQueryService.ValidationIssue::message)
        .singleElement()
        .asString()
        .contains("does not match member type");
  }

  @Test
  void rejectsFanoutUnsafeMetricDimensionCombination() {
    SemanticQuery query =
        new SemanticQuery(
            List.of("retail.total_revenue"),
            List.of(new SemanticQuery.DimensionSelection("retail.abc.name", null)),
            null,
            List.of(),
            10,
            "UTC");

    SemanticQueryService.CompilationResponse response =
        service.compile(principal, query, "trace-incompatible");

    assertThat(response.valid()).isFalse();
    assertThat(response.errors())
        .extracting(SemanticQueryService.ValidationIssue::code)
        .containsExactly("INCOMPATIBLE_METRICS_AND_DIMENSIONS");
  }

  @Test
  void reportsAmbiguousJoinPaths() {
    LinkedHashMap<String, SemanticModel.SemanticObject> objects =
        new LinkedHashMap<>(model.objects());
    SemanticModel.SemanticObject orders = objects.get("orders");
    List<SemanticModel.Relationship> relationships =
        new java.util.ArrayList<>(orders.spec().relationships());
    relationships.add(
        new SemanticModel.Relationship(
            "alternate_order_customer",
            "customers",
            List.of("customer_id"),
            List.of("customer_id"),
            SemanticModel.Cardinality.many_to_one,
            SemanticModel.JoinType.left));
    objects.put(
        "orders",
        new SemanticModel.SemanticObject(
            orders.version(),
            orders.kind(),
            orders.metadata(),
            new SemanticModel.ObjectSpec(
                orders.spec().source(),
                orders.spec().primaryKey(),
                orders.spec().dimensions(),
                relationships),
            orders.file()));
    SemanticModel ambiguous =
        new SemanticModel(
            model.project(), objects, model.metrics(), model.revision(), model.loadedAt());
    SemanticCatalog ambiguousCatalog = mock(SemanticCatalog.class);
    when(ambiguousCatalog.model()).thenReturn(ambiguous);
    SemanticQueryService ambiguousService =
        new SemanticQueryService(
            ambiguousCatalog,
            new AllowPolicy(),
            new AstSemanticSqlCompiler(),
            executor,
            new SemanticProperties("semantic-model", "test", null, null, null));

    SemanticQueryService.CompilationResponse response =
        ambiguousService.compile(
            principal,
            new SemanticQuery(
                List.of("retail.total_revenue"),
                List.of(new SemanticQuery.DimensionSelection("retail.customers.country", null)),
                null,
                List.of(),
                10,
                "UTC"),
            "trace-ambiguous");

    assertThat(response.errors())
        .extracting(SemanticQueryService.ValidationIssue::code)
        .containsExactly("AMBIGUOUS_JOIN_PATH");
  }

  @Test
  void preservesDecimalResultsAndReportsTruncation() {
    when(executor.execute(
            org.mockito.ArgumentMatchers.any(CompiledQuery.class),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            new QueryResult(
                List.of(
                    new QueryResult.Column("retail.customers.country", Types.VARCHAR, "varchar", true),
                    new QueryResult.Column(
                        "retail.total_revenue", Types.DECIMAL, "decimal(18,2)", true)),
                List.of(
                    List.of("FI", new BigDecimal("1289000.20")),
                    List.of("SE", new BigDecimal("249.00")),
                    List.of("GB", new BigDecimal("168.00"))),
                "trino-query-1"));

    SemanticQuery query =
        new SemanticQuery(
            List.of("retail.total_revenue"),
            List.of(new SemanticQuery.DimensionSelection("retail.customers.country", null)),
            null,
            List.of(
                new SemanticQuery.OrderBy(
                    "retail.total_revenue", SemanticQuery.SortDirection.DESC)),
            2,
            "UTC");
    SemanticQueryService.MetricQueryResponse response =
        service.query(principal, query, "trace-query");

    assertThat(response.truncated()).isTrue();
    assertThat(response.rowCount()).isEqualTo(2);
    assertThat(response.rows().getFirst().get(1)).isEqualTo(new BigDecimal("1289000.20"));
    assertThat(response.columns())
        .extracting(SemanticQueryService.ResultColumn::role)
        .containsExactly("dimension", "metric");
    assertThat(response.queryId()).isEqualTo("trino-query-1");
  }

  @Test
  void dimensionValuesReusePlannerAndRequireSearchForHighCardinality() {
    assertThatThrownBy(
            () ->
                service.dimensionValues(
                    principal,
                    new SemanticQueryService.DimensionValuesRequest(
                        "retail.orders.order_id", List.of(), null, null, 20, null),
                    "trace-high-cardinality"))
        .isInstanceOf(SemanticException.class)
        .extracting(exception -> ((SemanticException) exception).code())
        .isEqualTo(SemanticErrorCode.HIGH_CARDINALITY_SEARCH_REQUIRED);

    when(executor.execute(
            org.mockito.ArgumentMatchers.any(CompiledQuery.class),
            org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            invocation -> {
              CompiledQuery compiled = invocation.getArgument(0);
              List<Object> parameters = invocation.getArgument(1);
              assertThat(compiled.trinoSql()).contains("STARTS_WITH(LOWER(");
              assertThat(parameters).containsExactly("F");
              return new QueryResult(
                  List.of(new QueryResult.Column("country", Types.VARCHAR, "varchar", true)),
                  List.of(List.of("FI")),
                  "dimension-query");
            });

    SemanticQueryService.DimensionValuesResponse response =
        service.dimensionValues(
            principal,
            new SemanticQueryService.DimensionValuesRequest(
                "retail.customers.country",
                List.of("retail.total_revenue"),
                "F",
                null,
                20,
                null),
            "trace-values");

    assertThat(response.values())
        .extracting(SemanticQueryService.DimensionValue::value)
        .containsExactly("FI");
  }

  @Test
  void appliesTrustedRowPoliciesToQueriesAndDimensionValues() {
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    QueryExecutor policyExecutor = mock(QueryExecutor.class);
    SemanticQueryService policyService =
        new SemanticQueryService(
            catalog,
            new CountryRowPolicy(),
            new AstSemanticSqlCompiler(),
            policyExecutor,
            new SemanticProperties("semantic-model", "test", null, null, null));
    when(policyExecutor.execute(anyCompiled(), org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            invocation -> {
              CompiledQuery compiled = invocation.getArgument(0);
              List<Object> parameters = invocation.getArgument(1);
              assertThat(compiled.trinoSql()).contains("JOIN \"postgres\".\"public\".\"customers\"");
              assertThat(parameters).contains("FI");
              if (compiled.trinoSql().contains("STARTS_WITH")) {
                assertThat(parameters).containsExactly("p", "FI");
                return new QueryResult(
                    List.of(new QueryResult.Column("status", Types.VARCHAR, "varchar", true)),
                    List.of(List.of("paid")),
                    "policy-dimension-query");
              }
              return new QueryResult(
                  List.of(new QueryResult.Column("revenue", Types.DECIMAL, "decimal(18,2)", true)),
                  List.of(List.of(new BigDecimal("100.00"))),
                  "policy-metric-query");
            });

    SemanticQueryService.MetricQueryResponse metricResult =
        policyService.query(
            principal,
            new SemanticQuery(
                List.of("retail.total_revenue"),
                List.of(),
                null,
                List.of(),
                10,
                "UTC"),
            "trace-policy-metric");
    SemanticQueryService.DimensionValuesResponse dimensionResult =
        policyService.dimensionValues(
            principal,
            new SemanticQueryService.DimensionValuesRequest(
                "retail.orders.status", List.of(), "p", null, 10, null),
            "trace-policy-dimension");

    assertThat(metricResult.rows()).containsExactly(List.of(new BigDecimal("100.00")));
    assertThat(dimensionResult.values())
        .extracting(SemanticQueryService.DimensionValue::value)
        .containsExactly("paid");
  }

  private CompiledQuery anyCompiled() {
    return org.mockito.ArgumentMatchers.any(CompiledQuery.class);
  }

  private SemanticQuery governedQuery(int limit) {
    return new SemanticQuery(
        List.of("retail.total_revenue"),
        List.of(
            new SemanticQuery.DimensionSelection(
                "retail.orders.created_at", SemanticQuery.TimeGranularity.MONTH),
            new SemanticQuery.DimensionSelection("retail.customers.country", null)),
        new SemanticQuery.FilterGroup(
            SemanticQuery.LogicalOperator.AND,
            List.of(
                new SemanticQuery.FilterCondition(
                    "retail.orders.created_at",
                    SemanticQuery.FilterOperator.BETWEEN,
                    List.of("2026-01-01", "2026-06-30")))),
        List.of(
            new SemanticQuery.OrderBy(
                "retail.total_revenue", SemanticQuery.SortDirection.DESC)),
        limit,
        "Europe/Moscow");
  }

  static class AllowPolicy implements SemanticAccessPolicy {
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
  }

  static final class CountryRowPolicy extends AllowPolicy {
    @Override
    public boolean canReadDimension(
        SemanticPrincipal principal,
        SemanticModel model,
        SemanticModel.SemanticObject object,
        SemanticModel.Dimension dimension) {
      return !dimension.name().equals("country")
          && super.canReadDimension(principal, model, object, dimension);
    }

    @Override
    public List<SemanticQuery.FilterCondition> requiredFilters(
        SemanticPrincipal principal, SemanticModel model) {
      return List.of(
          new SemanticQuery.FilterCondition(
              "retail.customers.country",
              SemanticQuery.FilterOperator.EQ,
              List.of("FI")));
    }

    @Override
    public List<String> appliedPolicySummary(SemanticPrincipal principal) {
      return List.of("Country access scope applied");
    }
  }
}
