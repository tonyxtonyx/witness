package com.acme.semantic.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.SemanticModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticDiscoveryAndLineageTest {
  private final SemanticPrincipal principal = SemanticPrincipal.authenticated("test-key");
  private SemanticModel model;
  private SemanticCatalog catalog;

  @BeforeEach
  void setUp() {
    model = withMetricAlias(TestModels.demo());
    catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
  }

  @Test
  void searchSupportsExactAliasesAndExcludesInaccessibleObjects() {
    SemanticAccessPolicy policy = new DenyFinancePolicy();
    SemanticMetadataService service = new SemanticMetadataService(catalog, policy);

    SemanticMetadataService.SearchPage hidden =
        service.search(
            principal,
            new SemanticMetadataService.SearchRequest(
                "bookings", Set.of(), null, Set.of(), null, 20, null));
    SemanticMetadataService.SearchPage visible =
        service.search(
            principal,
            new SemanticMetadataService.SearchRequest(
                "order volume", Set.of(), null, Set.of(), null, 20, null));

    assertThat(hidden.results()).isEmpty();
    assertThat(visible.results())
        .extracting(SemanticMetadataService.SearchResult::id)
        .containsExactly("retail.order_count");
    assertThat(visible.results().getFirst().matchReasons()).containsExactly("exact alias match");
  }

  @Test
  void getAndMetricContextUseStableIdsAndActualJoinCompatibility() {
    SemanticMetadataService service =
        new SemanticMetadataService(catalog, new SemanticQueryServiceTest.AllowPolicy());

    SemanticMetadataService.ObjectDefinition object =
        service.get(principal, "retail.orders.created_at");
    SemanticMetadataService.MetricContext context =
        service.metricContext(principal, "retail.total_revenue");

    assertThat(object.type()).isEqualTo(SemanticMetadataService.ObjectType.dimension);
    assertThat(object.definition()).containsEntry("semanticObject", "retail.orders");
    assertThat(context.compatibleDimensions())
        .extracting(SemanticMetadataService.CompatibleDimension::id)
        .contains("retail.customers.country")
        .doesNotContain("retail.abc.name");
  }

  @Test
  void lineageIsCycleSafeBoundedAndDoesNotLeakPhysicalNodes() {
    SemanticLineageService service =
        new SemanticLineageService(
            catalog,
            new SemanticQueryServiceTest.AllowPolicy(),
            new SemanticProperties("semantic-model", "test", null, null, null));

    SemanticLineageService.LineageResult result =
        service.lineage(
            principal,
            new SemanticLineageService.LineageRequest(
                "retail.orders",
                SemanticLineageService.Direction.both,
                3,
                Set.of(),
                true));

    assertThat(result.nodes()).extracting(SemanticLineageService.LineageNode::id).doesNotHaveDuplicates();
    assertThat(result.nodes())
        .extracting(SemanticLineageService.LineageNode::type)
        .doesNotContain(SemanticLineageService.NodeType.physical_object);
    assertThat(result.edges()).isSortedAccordingTo(
        java.util.Comparator.comparing(SemanticLineageService.LineageEdge::from)
            .thenComparing(SemanticLineageService.LineageEdge::to)
            .thenComparing(SemanticLineageService.LineageEdge::type));
  }

  @Test
  void lineageTraversesUpstreamAndDownstreamDeterministically() {
    SemanticLineageService service =
        new SemanticLineageService(
            catalog,
            new SemanticQueryServiceTest.AllowPolicy(),
            new SemanticProperties("semantic-model", "test", null, null, null));

    SemanticLineageService.LineageResult upstream =
        service.lineage(
            principal,
            new SemanticLineageService.LineageRequest(
                "retail.total_revenue",
                SemanticLineageService.Direction.upstream,
                2,
                Set.of(),
                false));
    SemanticLineageService.LineageResult downstream =
        service.lineage(
            principal,
            new SemanticLineageService.LineageRequest(
                "retail.orders",
                SemanticLineageService.Direction.downstream,
                2,
                Set.of(),
                false));

    assertThat(upstream.nodes())
        .extracting(SemanticLineageService.LineageNode::id)
        .contains("retail.orders");
    assertThat(downstream.nodes())
        .extracting(SemanticLineageService.LineageNode::id)
        .contains("retail.total_revenue");
  }

  @Test
  void derivedSourceLineageIncludesEveryParsedPhysicalTableAndFailsClosed() {
    LinkedHashMap<String, SemanticModel.SemanticObject> objects =
        new LinkedHashMap<>(model.objects());
    objects.put(
        "retail.derived_orders",
        derivedObject(
            "derived_orders",
            "SELECT o.order_id, c.customer_id FROM postgres.public.orders o "
                + "JOIN lakehouse.analytics.customers c ON c.customer_id = o.customer_id"));
    objects.put(
        "retail.invalid_derived",
        derivedObject("invalid_derived", "SELECT order_id FROM orders"));
    SemanticModel derived =
        new SemanticModel(
            model.project(), objects, model.metrics(), model.revision(), model.loadedAt());
    SemanticCatalog derivedCatalog = mock(SemanticCatalog.class);
    when(derivedCatalog.model()).thenReturn(derived);
    SemanticLineageService service =
        new SemanticLineageService(
            derivedCatalog,
            new PhysicalLineagePolicy(),
            new SemanticProperties("semantic-model", "test", null, null, null));

    SemanticLineageService.LineageResult result =
        service.lineage(
            principal,
            new SemanticLineageService.LineageRequest(
                "retail.derived_orders",
                SemanticLineageService.Direction.upstream,
                1,
                Set.of(),
                true));
    SemanticLineageService.LineageResult invalid =
        service.lineage(
            principal,
            new SemanticLineageService.LineageRequest(
                "retail.invalid_derived",
                SemanticLineageService.Direction.upstream,
                1,
                Set.of(),
                true));

    assertThat(result.nodes())
        .extracting(SemanticLineageService.LineageNode::id)
        .contains("physical:postgres.public.orders", "physical:lakehouse.analytics.customers");
    assertThat(result.edges())
        .contains(
            new SemanticLineageService.LineageEdge(
                "physical:postgres.public.orders",
                "retail.derived_orders",
                "SOURCES",
                "physical"),
            new SemanticLineageService.LineageEdge(
                "physical:lakehouse.analytics.customers",
                "retail.derived_orders",
                "SOURCES",
                "physical"));
    assertThat(invalid.nodes())
        .extracting(SemanticLineageService.LineageNode::type)
        .doesNotContain(SemanticLineageService.NodeType.physical_object);
  }

  @Test
  void inaccessibleLineageRootDoesNotRevealExistence() {
    SemanticLineageService service =
        new SemanticLineageService(
            catalog,
            new DenyFinancePolicy(),
            new SemanticProperties("semantic-model", "test", null, null, null));

    assertThatThrownBy(
            () ->
                service.lineage(
                    principal,
                    new SemanticLineageService.LineageRequest(
                        "retail.total_revenue",
                        SemanticLineageService.Direction.upstream,
                        2,
                        Set.of(),
                        false)))
        .isInstanceOf(SemanticException.class)
        .extracting(exception -> ((SemanticException) exception).code())
        .isEqualTo(SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND);
  }

  @Test
  void boundsShortestPathExpansionOnDenseRelationshipGraph() {
    LinkedHashMap<String, SemanticModel.SemanticObject> objects = new LinkedHashMap<>();
    for (int i = 0; i <= 100; i++) {
      String name = "node_" + i;
      List<SemanticModel.Relationship> relationships = new java.util.ArrayList<>();
      for (int j = 0; j <= 100; j++) {
        if (i == j) continue;
        relationships.add(
            new SemanticModel.Relationship(
                "to_" + j,
                "node_" + j,
                List.of("id"),
                List.of("id"),
                SemanticModel.Cardinality.many_to_one,
                SemanticModel.JoinType.left));
      }
      objects.put(name, graphObject(name, relationships));
    }
    objects.put("unreachable", graphObject("unreachable", List.of()));
    SemanticModel dense =
        new SemanticModel(
            model.project(), objects, java.util.Map.of(), model.revision(), model.loadedAt());

    assertThatThrownBy(
            () ->
                new SemanticRelationshipGraph(dense)
                    .uniqueShortestPath("retail.node_0", "retail.unreachable"))
        .isInstanceOf(SemanticException.class)
        .hasMessageContaining("Relationship path search exceeded 10000 expanded states");
  }

  @Test
  void reportsOnlyABoundedDeterministicSetOfShortestPathCandidates() {
    LinkedHashMap<String, SemanticModel.SemanticObject> objects =
        new LinkedHashMap<>(model.objects());
    SemanticModel.SemanticObject orders = objects.get("retail.orders");
    List<SemanticModel.Relationship> relationships =
        new java.util.ArrayList<>(orders.spec().relationships());
    for (int i = 0; i < 8; i++) {
      relationships.add(
          new SemanticModel.Relationship(
              "alternate_customer_" + i,
              "customers",
              List.of("customer_id"),
              List.of("customer_id"),
              SemanticModel.Cardinality.many_to_one,
              SemanticModel.JoinType.left));
    }
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
    SemanticModel candidates =
        new SemanticModel(
            model.project(), objects, model.metrics(), model.revision(), model.loadedAt());

    SemanticRelationshipGraph.PathResult result =
        new SemanticRelationshipGraph(candidates)
            .uniqueShortestPath("retail.orders", "retail.customers");

    assertThat(result.ambiguous()).isTrue();
    assertThat(result.candidatePaths())
        .extracting(path -> path.getFirst().relationship().name())
        .containsExactly(
            "alternate_customer_0",
            "alternate_customer_1",
            "alternate_customer_2",
            "alternate_customer_3",
            "alternate_customer_4");
  }

  private SemanticModel.SemanticObject graphObject(
      String name, List<SemanticModel.Relationship> relationships) {
    return new SemanticModel.SemanticObject(
        1,
        "object",
        new SemanticModel.Metadata(
            name, "retail", name, "Graph test object", "test", List.of()),
        new SemanticModel.ObjectSpec(
            new SemanticModel.Source("test", "test", name),
            List.of("id"),
            List.of(new SemanticModel.Dimension("id", "ID", "ID", "bigint", "id", false)),
            relationships),
        "objects/" + name + ".yaml");
  }

  private SemanticModel.SemanticObject derivedObject(String name, String select) {
    return new SemanticModel.SemanticObject(
        1,
        "object",
        new SemanticModel.Metadata(
            name, "retail", name, "Derived lineage test object", "test", List.of()),
        new SemanticModel.ObjectSpec(
            new SemanticModel.Source(null, null, null, select),
            List.of("order_id"),
            List.of(
                new SemanticModel.Dimension(
                    "order_id", "Order ID", "Order ID", "bigint", "order_id", false)),
            List.of()),
        "objects/" + name + ".yaml");
  }

  private SemanticModel withMetricAlias(SemanticModel source) {
    LinkedHashMap<String, SemanticModel.Metric> metrics = new LinkedHashMap<>(source.metrics());
    SemanticModel.Metric revenue = metrics.get("retail.total_revenue");
    SemanticModel.Metadata revenueMetadata = revenue.metadata();
    metrics.put(
        "retail.total_revenue",
        new SemanticModel.Metric(
            revenue.version(),
            revenue.kind(),
            new SemanticModel.Metadata(
                revenueMetadata.name(),
                revenueMetadata.domain(),
                revenueMetadata.label(),
                revenueMetadata.description(),
                revenueMetadata.owner(),
                revenueMetadata.tags(),
                List.of("bookings")),
            revenue.spec(),
            revenue.file()));
    SemanticModel.Metric count = metrics.get("retail.order_count");
    SemanticModel.Metadata countMetadata = count.metadata();
    metrics.put(
        "retail.order_count",
        new SemanticModel.Metric(
            count.version(),
            count.kind(),
            new SemanticModel.Metadata(
                countMetadata.name(),
                countMetadata.domain(),
                countMetadata.label(),
                countMetadata.description(),
                countMetadata.owner(),
                countMetadata.tags(),
                List.of("order volume")),
            count.spec(),
            count.file()));
    return new SemanticModel(
        source.project(), source.objects(), metrics, source.revision(), source.loadedAt());
  }

  private static final class DenyFinancePolicy extends SemanticQueryServiceTest.AllowPolicy {
    @Override
    public boolean canReadMetric(
        SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
      return super.canReadMetric(principal, model, metric)
          && !metric.metadata().tags().contains("finance");
    }
  }

  private static final class PhysicalLineagePolicy
      extends SemanticQueryServiceTest.AllowPolicy {
    @Override
    public boolean canViewPhysicalLineage(SemanticPrincipal principal) {
      return true;
    }
  }
}
