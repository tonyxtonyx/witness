package com.acme.semantic.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.semantic.api.ApiController;
import com.acme.semantic.api.ApiSecurityFilter;
import com.acme.semantic.api.WorkspaceApiController;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.AstSemanticSqlCompiler;
import com.acme.semantic.compiler.SqlCompilationException;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.ModelParseException;
import com.acme.semantic.model.ModelParser;
import com.acme.semantic.model.ModelRevision;
import com.acme.semantic.model.SemanticModel;
import com.acme.semantic.validation.DefaultModelValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class DomainQualifiedIdentityTest {
  private final SemanticPrincipal principal = SemanticPrincipal.authenticated("test-key");
  private final SemanticAccessPolicy policy = new SemanticQueryServiceTest.AllowPolicy();
  private SemanticModel model;
  private SemanticCatalog catalog;

  @BeforeEach
  void setUp() {
    model = new ModelParser().parse(new ModelRevision("qualified", files()));
    catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
  }

  @Test
  void sameNamedObjectsAndMetricsCoexistAndRemainIndependentlyDiscoverable() {
    assertThat(model.objects()).containsKeys("retail.orders", "finance.orders");
    assertThat(model.metrics()).containsKeys("retail.total", "finance.total");
    assertThat(model.metrics()).containsKey("analytics.finance_total");
    assertThat(new DefaultModelValidator().validate(model).valid()).isTrue();
    assertThat(model.resolveObject("orders").ambiguous()).isTrue();
    assertThat(model.resolveObject("orders").candidates())
        .containsExactly("finance.orders", "retail.orders");
    assertThat(model.resolveObject("orders", "retail").value())
        .isSameAs(model.objects().get("retail.orders"));
    assertThat(
            new SemanticRelationshipGraph(model)
                .uniqueShortestPath("analytics.cross_report", "finance.orders")
                .path())
        .isPresent();

    AstSemanticSqlCompiler compiler = new AstSemanticSqlCompiler();
    assertThat(compiler.compile("SELECT amount, total FROM retail.orders", model).trinoSql())
        .contains("\"postgres\".\"retail_raw\".\"retail_orders\"")
        .doesNotContain("finance_orders");
    assertThat(compiler.compile("SELECT amount, total FROM finance.orders", model).trinoSql())
        .contains("\"postgres\".\"finance_raw\".\"finance_orders\"")
        .doesNotContain("retail_orders");
    assertThat(compiler.compile("SELECT finance_total FROM finance.orders", model).trinoSql())
        .contains("SUM(\"orders\".\"amount\")")
        .contains("finance_orders");
    SemanticAccessPolicy visibleSql =
        new SemanticQueryServiceTest.AllowPolicy() {
          @Override
          public boolean canViewCompiledSql(SemanticPrincipal ignored) {
            return true;
          }
        };
    SemanticQueryService queryService =
        new SemanticQueryService(
            catalog,
            visibleSql,
            compiler,
            mock(com.acme.semantic.execution.QueryExecutor.class),
            new SemanticProperties("semantic-model", "test", null, null, null));
    SemanticQueryService.CompilationResponse joined =
        queryService.compile(
            principal,
            new SemanticQuery(
                List.of("retail.total"),
                List.of(new SemanticQuery.DimensionSelection("finance.orders.id", null)),
                null,
                List.of(),
                10,
                "UTC"),
            "qualified-aliases");
    assertThat(joined.valid()).isTrue();
    assertThat(joined.compiledSql())
        .contains("\"o_6_retail_orders\"")
        .contains("\"o_7_finance_orders\"");

    SemanticMetadataService metadata = new SemanticMetadataService(catalog, policy);
    assertThat(
            metadata
                .search(
                    principal,
                    new SemanticMetadataService.SearchRequest(
                        "orders",
                        Set.of(SemanticMetadataService.ObjectType.semantic_object),
                        null,
                        Set.of(),
                        null,
                        20,
                        null))
                .results())
        .extracting(SemanticMetadataService.SearchResult::id)
        .containsExactlyInAnyOrder("retail.orders", "finance.orders");
    assertThat(metadata.get(principal, "retail.orders").domain()).isEqualTo("retail");
    assertThat(metadata.get(principal, "finance.orders").domain()).isEqualTo("finance");
    assertThat(metadata.get(principal, "analytics.finance_total").definition())
        .containsEntry("baseObject", "finance.orders");

    SemanticLineageService lineage =
        new SemanticLineageService(
            catalog,
            policy,
            new SemanticProperties("semantic-model", "test", null, null, null));
    assertThat(
            lineage
                .lineage(
                    principal,
                    new SemanticLineageService.LineageRequest(
                        "retail.total",
                        SemanticLineageService.Direction.upstream,
                        1,
                        Set.of(),
                        false))
                .nodes())
        .extracting(SemanticLineageService.LineageNode::id)
        .contains("retail.orders")
        .doesNotContain("finance.orders");
    assertThat(
            lineage
                .lineage(
                    principal,
                    new SemanticLineageService.LineageRequest(
                        "finance.total",
                        SemanticLineageService.Direction.upstream,
                        1,
                        Set.of(),
                        false))
                .nodes())
        .extracting(SemanticLineageService.LineageNode::id)
        .contains("finance.orders")
        .doesNotContain("retail.orders");
  }

  @Test
  void bareCompilerAndRestReferencesFailWithQualifiedCandidates() {
    assertThatThrownBy(
            () ->
                new AstSemanticSqlCompiler()
                    .compile("SELECT amount FROM orders", model))
        .isInstanceOf(SqlCompilationException.class)
        .satisfies(
            error -> assertThat(((SqlCompilationException) error).sqlState()).isEqualTo("42702"))
        .hasMessageContaining("finance.orders")
        .hasMessageContaining("retail.orders");

    ApiController api = new ApiController(catalog, null, null, null, null, null, policy);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(ApiSecurityFilter.PRINCIPAL_ATTRIBUTE, "api-key");
    assertThat(api.object("retail.orders", request).metadata().domain()).isEqualTo("retail");
    assertThat(api.metric("finance.total", request).metadata().domain()).isEqualTo("finance");
    assertThat(api.metrics(null, null, null, "finance.orders", null, request))
        .extracting(metric -> metric.metadata().domain())
        .containsExactly("finance", "analytics");
    assertThat(api.metrics(null, null, null, "finance.orders", null, request))
        .extracting(metric -> metric.spec().baseObject())
        .containsOnly("finance.orders");
    assertThatThrownBy(() -> api.object("orders", request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(409))
        .hasMessageContaining("finance.orders")
        .hasMessageContaining("retail.orders");
    assertThatThrownBy(() -> api.metric("total", request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("finance.total")
        .hasMessageContaining("retail.total");
    assertThatThrownBy(() -> api.metrics(null, null, null, "orders", null, request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Ambiguous object reference");

    WorkspaceApiController workspace =
        new WorkspaceApiController(
            catalog,
            null,
            null,
            null,
            new SemanticProperties("semantic-model", "test", null, null, null),
            policy);
    assertThat(workspace.search("orders", "object", null, false, request))
        .extracting(WorkspaceApiController.SearchResult::path)
        .containsExactlyInAnyOrder("/objects/retail.orders", "/objects/finance.orders");
    assertThat(workspace.source("retail.orders", request).file())
        .isEqualTo("objects/retail-orders.yaml");
    assertThatThrownBy(() -> workspace.source("orders", request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("finance.orders")
        .hasMessageContaining("retail.orders");
    assertThat(
            workspace
                .validate(
                    new WorkspaceApiController.ValidationRequest("amount", "orders", "sum"),
                    request)
                .errors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.code()).isEqualTo("AMBIGUOUS_OBJECT");
              assertThat(error.message()).contains("finance.orders", "retail.orders");
            });
  }

  @Test
  void parserRejectsOnlySameDomainDuplicatesAndValidatorNamesAmbiguousReferences() {
    Map<String, String> duplicateObjects = new LinkedHashMap<>(files());
    duplicateObjects.put("objects/retail-orders-copy.yaml", object("orders", "retail", "copy"));
    assertThatThrownBy(
            () -> new ModelParser().parse(new ModelRevision("duplicate", duplicateObjects)))
        .isInstanceOf(ModelParseException.class)
        .satisfies(
            error ->
                assertThat(((ModelParseException) error).code()).isEqualTo("DUPLICATE_OBJECT"))
        .hasMessageContaining("retail.orders");

    Map<String, String> duplicateMetrics = new LinkedHashMap<>(files());
    duplicateMetrics.put("metrics/retail-total-copy.yaml", metric("total", "retail", "orders"));
    assertThatThrownBy(
            () -> new ModelParser().parse(new ModelRevision("duplicate", duplicateMetrics)))
        .isInstanceOf(ModelParseException.class)
        .satisfies(
            error ->
                assertThat(((ModelParseException) error).code()).isEqualTo("DUPLICATE_METRIC"))
        .hasMessageContaining("retail.total");

    Map<String, String> ambiguous = new LinkedHashMap<>(files());
    ambiguous.put("objects/report.yaml", report());
    ambiguous.put("metrics/ambiguous-total.yaml", metric("ambiguous_total", "analytics", "orders"));
    ambiguous.put("metrics/object-collision.yaml", metric("orders", "retail", "orders"));
    var result =
        new DefaultModelValidator()
            .validate(new ModelParser().parse(new ModelRevision("ambiguous", ambiguous)));
    assertThat(result.errors())
        .anySatisfy(
            error -> {
              assertThat(error.code()).isEqualTo("UNKNOWN_TARGET");
              assertThat(error.message()).contains("finance.orders", "retail.orders");
            })
        .anySatisfy(
            error -> {
              assertThat(error.code()).isEqualTo("UNKNOWN_BASE_OBJECT");
              assertThat(error.message()).contains("finance.orders", "retail.orders");
            })
        .anyMatch(error -> error.code().equals("GLOBAL_ID_COLLISION"));
  }

  private Map<String, String> files() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("project.yaml", project());
    files.put("objects/retail-orders.yaml", retailOrders());
    files.put("objects/finance-orders.yaml", object("orders", "finance", "finance_orders"));
    files.put("objects/cross-report.yaml", crossReport());
    files.put("metrics/retail-total.yaml", metric("total", "retail", "orders"));
    files.put("metrics/finance-total.yaml", metric("total", "finance", "finance.orders"));
    files.put(
        "metrics/analytics-finance-total.yaml",
        metric("finance_total", "analytics", "finance.orders"));
    return files;
  }

  private String project() {
    return """
        version: 1
        kind: project
        metadata:
          name: domains
          label: Domains
          description: Domain identity test
          owner: test
        spec:
          semanticSchema: semantic
          trino: {defaultCatalog: postgres, defaultSchema: public}
        """;
  }

  private String object(String name, String domain, String table) {
    return """
        version: 1
        kind: object
        metadata:
          name: %s
          domain: %s
          label: %s %s
          description: %s object
          owner: test
        spec:
          source: {catalog: postgres, schema: %s_raw, table: %s}
          primaryKey: [id]
          dimensions:
            - {name: id, label: ID, type: bigint, sql: id, nullable: false}
            - {name: amount, label: Amount, type: "decimal(18,2)", sql: amount}
        """.formatted(name, domain, domain, name, domain, domain, table);
  }

  private String metric(String name, String domain, String baseObject) {
    return """
        version: 1
        kind: metric
        metadata:
          name: %s
          domain: %s
          label: %s %s
          description: %s metric
          owner: test
        spec:
          baseObject: %s
          aggregation: sum
          expression: amount
          resultType: "decimal(18,2)"
          format: currency
        """.formatted(name, domain, domain, name, domain, baseObject);
  }

  private String retailOrders() {
    return object("orders", "retail", "retail_orders")
        + """
          relationships:
            - name: finance_orders
              targetObject: finance.orders
              sourceFields: [id]
              targetFields: [id]
              cardinality: many_to_one
              defaultJoinType: left
        """;
  }

  private String report() {
    return """
        version: 1
        kind: object
        metadata:
          name: ambiguous_report
          domain: analytics
          label: Report
          description: Ambiguous relationship test
          owner: test
        spec:
          source: {catalog: postgres, schema: analytics_raw, table: report}
          primaryKey: [id]
          dimensions:
            - {name: id, label: ID, type: bigint, sql: id, nullable: false}
            - {name: amount, label: Amount, type: "decimal(18,2)", sql: amount}
          relationships:
            - name: ambiguous_orders
              targetObject: orders
              sourceFields: [id]
              targetFields: [id]
              cardinality: many_to_one
              defaultJoinType: left
        """;
  }

  private String crossReport() {
    return """
        version: 1
        kind: object
        metadata:
          name: cross_report
          domain: analytics
          label: Cross-domain report
          description: Qualified cross-domain relationship test
          owner: test
        spec:
          source: {catalog: postgres, schema: analytics_raw, table: cross_report}
          primaryKey: [id]
          dimensions:
            - {name: id, label: ID, type: bigint, sql: id, nullable: false}
            - {name: amount, label: Amount, type: "decimal(18,2)", sql: amount}
          relationships:
            - name: finance_orders
              targetObject: finance.orders
              sourceFields: [id]
              targetFields: [id]
              cardinality: many_to_one
              defaultJoinType: left
        """;
  }
}
