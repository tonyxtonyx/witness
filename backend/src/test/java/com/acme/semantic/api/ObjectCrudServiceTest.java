package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.gitlab.LocalModelRepository;
import com.acme.semantic.model.ModelParser;
import com.acme.semantic.model.SemanticModel;
import com.acme.semantic.validation.DefaultModelValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectCrudServiceTest {
  @TempDir Path temporaryModel;

  private ObjectCrudService service;
  private SemanticCatalog catalog;

  @BeforeEach
  void setUp() throws Exception {
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
    catalog = new SemanticCatalog(repository, parser, validator);
    catalog.init();
    service = new ObjectCrudService(repository, catalog, parser, validator);
  }

  @Test
  void updatesObjectAndReloadsTheLocalCatalog() {
    SemanticModel.SemanticObject current = catalog.model().objects().get("customers");
    var metadata =
        new SemanticModel.Metadata(
            "customers",
            "retail",
            "Customer directory",
            "Governed customer master",
            "customer-platform",
            current.metadata().tags());

    var updated =
        service.update(
            "customers", new ObjectCrudService.ObjectInput(metadata, current.spec()));

    assertThat(updated.metadata().label()).isEqualTo("Customer directory");
    assertThat(catalog.model().objects().get("customers").metadata().owner())
        .isEqualTo("customer-platform");
  }

  @Test
  void movesObjectYamlWhenItsDomainChanges() {
    SemanticModel.SemanticObject current = catalog.model().objects().get("customers");
    var metadata =
        new SemanticModel.Metadata(
            "customers",
            "ai_rnd",
            current.metadata().label(),
            current.metadata().description(),
            current.metadata().owner(),
            current.metadata().tags());

    var updated =
        service.update(
            "customers", new ObjectCrudService.ObjectInput(metadata, current.spec()));

    assertThat(updated.metadata().domain()).isEqualTo("ai_rnd");
    assertThat(updated.file()).isEqualTo("domains/ai_rnd/objects/customers.yaml");
    assertThat(temporaryModel.resolve("objects/customers.yaml")).doesNotExist();
    assertThat(temporaryModel.resolve("domains/ai_rnd/objects/customers.yaml")).exists();
  }

  @Test
  void createsObjectAndReloadsTheLocalCatalog() {
    SemanticModel.SemanticObject source = catalog.model().objects().get("customers");
    var metadata =
        new SemanticModel.Metadata(
            "customer_profiles",
            "retail",
            "Customer profiles",
            "Customer profile view",
            "customer-platform",
            java.util.List.of("customer"));

    var created =
        service.create(new ObjectCrudService.ObjectInput(metadata, source.spec()));

    assertThat(created.metadata().name()).isEqualTo("customer_profiles");
    assertThat(catalog.model().objects()).containsKey("customer_profiles");
    assertThat(
            Files.exists(
                temporaryModel.resolve(
                    "domains/retail/objects/customer_profiles.yaml")))
        .isTrue();
  }

  @Test
  void createsDerivedObjectAndReloadsTheLocalCatalog() throws Exception {
    SemanticModel.SemanticObject source = catalog.model().objects().get("customers");
    var metadata =
        new SemanticModel.Metadata(
            "customer_orders",
            "retail",
            "Customer orders",
            "Orders enriched with customer attributes",
            "sales-analytics",
            java.util.List.of("sales", "customer"));
    var derivedSource =
        new SemanticModel.Source(
            null,
            null,
            null,
            """
            SELECT o.order_id, o.customer_id, c.country
            FROM postgres.public.orders o
            LEFT JOIN postgres.public.customers c ON c.customer_id = o.customer_id
            """);
    var derivedSpec =
        new SemanticModel.ObjectSpec(
            derivedSource,
            java.util.List.of(),
            source.spec().dimensions(),
            java.util.List.of());

    var created =
        service.create(new ObjectCrudService.ObjectInput(metadata, derivedSpec));

    assertThat(created.spec().source().derived()).isTrue();
    assertThat(created.spec().source().select()).contains("LEFT JOIN");
        assertThat(
            Files.readString(
                temporaryModel.resolve("domains/retail/objects/customer_orders.yaml")))
        .contains("select:")
        .contains("LEFT JOIN postgres.public.customers");
  }

  @Test
  void refusesToDeleteObjectReferencedByMetricsAndRelationships() {
    assertThatThrownBy(() -> service.delete("orders"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Base object does not exist: orders");

    assertThatThrownBy(() -> service.delete("customers"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Target object does not exist: customers");
  }
}
