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
import java.util.LinkedHashMap;
import java.util.List;
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
    SemanticModel ambiguous = ambiguousModel();
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
    SemanticQueryService.ValidationIssue issue = response.errors().getFirst();
    assertThat(issue.details().get("candidatePaths"))
        .isEqualTo(
            List.of(
                List.of("alternate_order_customer"),
                List.of("order_customer")));
    assertThat(issue.details())
        .containsEntry("from", "retail.orders")
        .containsEntry("to", "retail.customers");
    assertThat(issue.suggestions()).singleElement().asString().contains("joinPaths");

    assertThatThrownBy(
            () ->
                ambiguousService.query(
                    principal,
                    customerQuery(List.of()),
                    "trace-ambiguous-details"))
        .isInstanceOf(SemanticException.class)
        .satisfies(
            exception -> {
              SemanticException semantic = (SemanticException) exception;
              assertThat(semantic.code()).isEqualTo(SemanticErrorCode.AMBIGUOUS_JOIN_PATH);
              assertThat(semantic.details().get("candidatePaths"))
                  .isEqualTo(
                      List.of(
                          List.of("alternate_order_customer"),
                          List.of("order_customer")));
              assertThat(semantic.suggestions()).singleElement().asString().contains("joinPaths");
            });
  }

  @Test
  void usesValidatedExplicitJoinPathAndStillEnforcesFanout() {
    SemanticModel ambiguous = ambiguousModel();
    SemanticCatalog ambiguousCatalog = mock(SemanticCatalog.class);
    when(ambiguousCatalog.model()).thenReturn(ambiguous);
    SemanticQueryService ambiguousService = service(ambiguousCatalog, new AllowPolicy());

    SemanticQueryService.CompilationResponse selected =
        ambiguousService.compile(
            principal,
            customerQuery(
                List.of(
                    new SemanticQuery.JoinPath(
                        "retail.customers", List.of("order_customer")))),
            "trace-selected");
    SemanticQueryService.CompilationResponse fanout =
        service.compile(
            principal,
            new SemanticQuery(
                List.of("retail.total_revenue"),
                List.of(new SemanticQuery.DimensionSelection("retail.abc.name", null)),
                null,
                List.of(),
                10,
                "UTC",
                List.of(
                    new SemanticQuery.JoinPath(
                        "retail.abc", List.of("order_product", "abc_products")))),
            "trace-explicit-fanout");

    assertThat(selected.valid()).isTrue();
    assertThat(selected.plan().joinPath()).containsExactly("order_customer");
    assertThat(selected.normalizedQuery().joinPaths())
        .containsExactly(
            new SemanticQuery.JoinPath("retail.customers", List.of("order_customer")));
    assertThat(fanout.errors())
        .extracting(SemanticQueryService.ValidationIssue::code)
        .containsExactly("INCOMPATIBLE_METRICS_AND_DIMENSIONS");

    assertThatThrownBy(
            () ->
                ambiguousService.query(
                    principal,
                    customerQuery(
                        List.of(
                            new SemanticQuery.JoinPath(
                                "retail.customers", List.of("missing_relationship")))),
                    "trace-invalid-hop"))
        .isInstanceOf(SemanticException.class)
        .satisfies(
            exception -> {
              SemanticException semantic = (SemanticException) exception;
              assertThat(semantic.code()).isEqualTo(SemanticErrorCode.INVALID_SEMANTIC_QUERY);
              assertThat(semantic.getMessage()).contains("hop 0").contains("missing_relationship");
              assertThat(semantic.details())
                  .containsEntry("path", "$.joinPaths[0].via[0]");
            });
    assertThatThrownBy(
            () ->
                ambiguousService.query(
                    principal,
                    customerQuery(
                        List.of(
                            new SemanticQuery.JoinPath(
                                "retail.customers", List.of("order_product")))),
                    "trace-disconnected-hop"))
        .isInstanceOf(SemanticException.class)
        .hasMessageContaining("does not reach target retail.customers at hop 0: order_product")
        .satisfies(
            exception ->
                assertThat(((SemanticException) exception).code())
                    .isEqualTo(SemanticErrorCode.INVALID_SEMANTIC_QUERY));
  }

  @Test
  void convertsUtcTimestampsBeforeBucketingInRequestedTimezone() {
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    SemanticQueryService visibleService = service(catalog, new ExposeSqlPolicy());
    SemanticQuery query =
        new SemanticQuery(
            List.of("retail.total_revenue"),
            List.of(
                new SemanticQuery.DimensionSelection(
                    "retail.orders.created_at", SemanticQuery.TimeGranularity.DAY)),
            null,
            List.of(
                new SemanticQuery.OrderBy(
                    "retail.orders.created_at", SemanticQuery.SortDirection.ASC)),
            10,
            "Asia/Tokyo");

    SemanticQueryService.CompilationResponse response =
        visibleService.compile(principal, query, "trace-timezone");
    String expression =
        "CAST(DATE_TRUNC('day', AT_TIMEZONE(WITH_TIMEZONE(\"o_6_retail_orders\".\"created_at\", 'UTC'), 'Asia/Tokyo')) AS timestamp)";

    assertThat(response.valid()).isTrue();
    assertThat(response.compiledSql()).contains(expression);
    assertThat(response.compiledSql().split(java.util.regex.Pattern.quote(expression), -1))
        .hasSize(4);
    assertThat(response.warnings())
        .containsExactly(
            "Timestamps are assumed to be stored in UTC and are converted to Asia/Tokyo for time bucketing");

    SemanticQueryService.CompilationResponse utc =
        visibleService.compile(
            principal,
            new SemanticQuery(
                query.metrics(),
                query.dimensions(),
                query.filters(),
                query.orderBy(),
                query.limit(),
                "UTC"),
            "trace-utc");
    assertThat(utc.compiledSql()).doesNotContain("WITH_TIMEZONE");
    assertThat(utc.warnings()).isEmpty();

    SemanticModel dateModel = withCreatedAtType("date");
    SemanticCatalog dateCatalog = mock(SemanticCatalog.class);
    when(dateCatalog.model()).thenReturn(dateModel);
    SemanticQueryService.CompilationResponse date =
        service(dateCatalog, new ExposeSqlPolicy())
            .compile(principal, query, "trace-date");
    assertThat(date.compiledSql())
        .contains("DATE_TRUNC('day', \"o_6_retail_orders\".\"created_at\")")
        .doesNotContain("WITH_TIMEZONE");
    assertThat(date.warnings()).isEmpty();
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

  private SemanticQueryService service(
      SemanticCatalog catalog, SemanticAccessPolicy accessPolicy) {
    return new SemanticQueryService(
        catalog,
        accessPolicy,
        new AstSemanticSqlCompiler(),
        executor,
        new SemanticProperties("semantic-model", "test", null, null, null));
  }

  private SemanticModel ambiguousModel() {
    LinkedHashMap<String, SemanticModel.SemanticObject> objects =
        new LinkedHashMap<>(model.objects());
    SemanticModel.SemanticObject orders = objects.get("retail.orders");
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
        "retail.orders",
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
    return new SemanticModel(
        model.project(), objects, model.metrics(), model.revision(), model.loadedAt());
  }

  private SemanticModel withCreatedAtType(String type) {
    LinkedHashMap<String, SemanticModel.SemanticObject> objects =
        new LinkedHashMap<>(model.objects());
    SemanticModel.SemanticObject orders = objects.get("retail.orders");
    List<SemanticModel.Dimension> dimensions =
        orders.spec().dimensions().stream()
            .map(
                dimension ->
                    dimension.name().equals("created_at")
                        ? new SemanticModel.Dimension(
                            dimension.name(),
                            dimension.label(),
                            dimension.description(),
                            type,
                            dimension.sql(),
                            dimension.nullable())
                        : dimension)
            .toList();
    objects.put(
        "retail.orders",
        new SemanticModel.SemanticObject(
            orders.version(),
            orders.kind(),
            orders.metadata(),
            new SemanticModel.ObjectSpec(
                orders.spec().source(),
                orders.spec().primaryKey(),
                dimensions,
                orders.spec().relationships()),
            orders.file()));
    return new SemanticModel(
        model.project(), objects, model.metrics(), model.revision(), model.loadedAt());
  }

  private SemanticQuery customerQuery(List<SemanticQuery.JoinPath> joinPaths) {
    return new SemanticQuery(
        List.of("retail.total_revenue"),
        List.of(new SemanticQuery.DimensionSelection("retail.customers.country", null)),
        null,
        List.of(),
        10,
        "UTC",
        joinPaths);
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

  static final class ExposeSqlPolicy extends AllowPolicy {
    @Override
    public boolean canViewCompiledSql(SemanticPrincipal principal) {
      return true;
    }
  }
}
