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

  private SemanticModel withMetricAlias(SemanticModel source) {
    LinkedHashMap<String, SemanticModel.Metric> metrics = new LinkedHashMap<>(source.metrics());
    SemanticModel.Metric revenue = metrics.get("total_revenue");
    SemanticModel.Metadata revenueMetadata = revenue.metadata();
    metrics.put(
        "total_revenue",
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
    SemanticModel.Metric count = metrics.get("order_count");
    SemanticModel.Metadata countMetadata = count.metadata();
    metrics.put(
        "order_count",
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
}
