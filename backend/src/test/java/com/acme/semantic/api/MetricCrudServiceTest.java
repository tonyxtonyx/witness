package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.gitlab.LocalModelRepository;
import com.acme.semantic.model.ModelParser;
import com.acme.semantic.model.SemanticModel;
import com.acme.semantic.validation.DefaultModelValidator;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricCrudServiceTest {
  @TempDir Path temporaryModel;

  @Test
  void createsUpdatesAndDeletesValidatedYamlMetric() throws Exception {
    try (var files = Files.walk(Path.of("semantic-model"))) {
      for (Path source : files.filter(Files::isRegularFile).toList()) {
        Path target =
            temporaryModel.resolve(Path.of("semantic-model").relativize(source).toString());
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
      }
    }
    var properties = new SemanticProperties(temporaryModel.toString(), "test", null, null, null);
    var repository = new LocalModelRepository(properties);
    var parser = new ModelParser();
    var validator = new DefaultModelValidator();
    var catalog = new SemanticCatalog(repository, parser, validator);
    assertThat(catalog.reload().healthy()).isTrue();
    var service = new MetricCrudService(repository, catalog, parser, validator);
    var metadata =
        new SemanticModel.Metadata(
            "gross_revenue",
            "retail",
            "Gross revenue",
            "All order revenue",
            "finance",
            List.of("finance"));
    var spec =
        new SemanticModel.MetricSpec(
            "orders",
            SemanticModel.Aggregation.sum,
            "amount",
            "decimal(18,2)",
            "currency",
            List.of());

    assertThat(service.create(new MetricCrudService.MetricInput(metadata, spec)).file())
        .isEqualTo("domains/retail/metrics/gross_revenue.yaml");
    assertThat(Files.exists(temporaryModel.resolve("domains/retail/metrics/gross_revenue.yaml")))
        .isTrue();
    var updated =
        new SemanticModel.Metadata(
            "gross_revenue",
            "retail",
            "Gross merchandise value",
            "All order revenue",
            "finance",
            List.of("finance"));
    assertThat(
            service
                .update("gross_revenue", new MetricCrudService.MetricInput(updated, spec))
                .metadata()
                .label())
        .isEqualTo("Gross merchandise value");
    service.delete("gross_revenue");
    assertThat(catalog.model().metrics()).doesNotContainKey("gross_revenue");
    assertThat(Files.exists(temporaryModel.resolve("domains/retail/metrics/gross_revenue.yaml")))
        .isFalse();
  }
}
