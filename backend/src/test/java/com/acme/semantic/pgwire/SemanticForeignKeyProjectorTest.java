package com.acme.semantic.pgwire;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.semantic.model.SemanticModel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticForeignKeyProjectorTest {
  private final SemanticForeignKeyProjector projector = new SemanticForeignKeyProjector();

  @Test
  void projectsCompositeCrossDomainManyToOneFromDerivedObject() {
    SemanticModel.SemanticObject accounts =
        object(
            "accounts",
            "crm",
            List.of("tenant_id", "account_id"),
            List.of(dimension("tenant_id"), dimension("account_id")),
            List.of(),
            false);
    SemanticModel.SemanticObject orders =
        object(
            "orders",
            "retail",
            List.of("order_id"),
            List.of(
                dimension("order_id"), dimension("tenant_id"), dimension("account_id")),
            List.of(
                relationship(
                    "order_account",
                    "accounts",
                    List.of("tenant_id", "account_id"),
                    List.of("tenant_id", "account_id"),
                    SemanticModel.Cardinality.many_to_one)),
            true);

    var keys = projector.project(model(accounts, orders));

    assertThat(keys)
        .singleElement()
        .satisfies(
            key -> {
              assertThat(key.name()).isEqualTo("order_account");
              assertThat(key.foreignKeyDomain()).isEqualTo("retail");
              assertThat(key.foreignKeyObject()).isEqualTo("orders");
              assertThat(key.foreignKeyFields()).containsExactly("tenant_id", "account_id");
              assertThat(key.primaryKeyDomain()).isEqualTo("crm");
              assertThat(key.primaryKeyObject()).isEqualTo("accounts");
              assertThat(key.primaryKeyFields()).containsExactly("tenant_id", "account_id");
              assertThat(key.declaredBy()).isEqualTo("retail.orders");
              assertThat(key.enforced()).isFalse();
            });
  }

  @Test
  void invertsOneToManySoTheManySideOwnsTheForeignKey() {
    SemanticModel.SemanticObject orders =
        object(
            "orders",
            "retail",
            List.of("order_id"),
            List.of(dimension("order_id"), dimension("customer_id")),
            List.of(),
            false);
    SemanticModel.SemanticObject customers =
        object(
            "customers",
            "crm",
            List.of("customer_id"),
            List.of(dimension("customer_id")),
            List.of(
                relationship(
                    "customer_orders",
                    "orders",
                    List.of("customer_id"),
                    List.of("customer_id"),
                    SemanticModel.Cardinality.one_to_many)),
            false);

    var keys = projector.project(model(customers, orders));

    assertThat(keys)
        .singleElement()
        .satisfies(
            key -> {
              assertThat(key.foreignKeyDomain()).isEqualTo("retail");
              assertThat(key.foreignKeyObject()).isEqualTo("orders");
              assertThat(key.foreignKeyFields()).containsExactly("customer_id");
              assertThat(key.primaryKeyDomain()).isEqualTo("crm");
              assertThat(key.primaryKeyObject()).isEqualTo("customers");
              assertThat(key.primaryKeyFields()).containsExactly("customer_id");
              assertThat(key.semanticCardinality())
                  .isEqualTo(SemanticModel.Cardinality.one_to_many);
              assertThat(key.declaredBy()).isEqualTo("crm.customers");
            });
  }

  @Test
  void projectsOneToOneAndOmitsManyToMany() {
    SemanticModel.SemanticObject users =
        object(
            "users",
            "identity",
            List.of("user_id"),
            List.of(dimension("user_id")),
            List.of(),
            false);
    SemanticModel.SemanticObject profiles =
        object(
            "profiles",
            "identity",
            List.of("user_id"),
            List.of(dimension("user_id")),
            List.of(
                relationship(
                    "profile_user",
                    "users",
                    List.of("user_id"),
                    List.of("user_id"),
                    SemanticModel.Cardinality.one_to_one)),
            false);
    SemanticModel.SemanticObject groups =
        object(
            "groups",
            "identity",
            List.of("group_id"),
            List.of(dimension("group_id")),
            List.of(
                relationship(
                    "group_users",
                    "users",
                    List.of("group_id"),
                    List.of("user_id"),
                    SemanticModel.Cardinality.many_to_many)),
            false);

    var keys = projector.project(model(users, profiles, groups));

    assertThat(keys).extracting(SemanticForeignKeyProjector.VirtualForeignKey::name)
        .containsExactly("profile_user");
    assertThat(keys.getFirst().foreignKeyObject()).isEqualTo("profiles");
    assertThat(keys.getFirst().primaryKeyObject()).isEqualTo("users");
  }

  private SemanticModel model(SemanticModel.SemanticObject... objects) {
    Map<String, SemanticModel.SemanticObject> objectMap = new LinkedHashMap<>();
    for (var object : objects) objectMap.put(object.metadata().name(), object);
    SemanticModel.Metadata projectMetadata =
        new SemanticModel.Metadata(
            "test", "semantic", "Test", "Test project", "test", List.of("test"));
    SemanticModel.Project project =
        new SemanticModel.Project(
            1,
            "project",
            projectMetadata,
            new SemanticModel.ProjectSpec(
                "semantic", new SemanticModel.TrinoDefaults("postgres", "public")));
    return new SemanticModel(project, objectMap, Map.of(), "test", Instant.EPOCH);
  }

  private SemanticModel.SemanticObject object(
      String name,
      String domain,
      List<String> primaryKey,
      List<SemanticModel.Dimension> dimensions,
      List<SemanticModel.Relationship> relationships,
      boolean derived) {
    SemanticModel.Metadata metadata =
        new SemanticModel.Metadata(
            name, domain, name, "Test object " + name, "test", List.of("test"));
    SemanticModel.Source source =
        derived
            ? new SemanticModel.Source(
                null, null, null, "SELECT order_id, tenant_id, account_id FROM x.y.orders")
            : new SemanticModel.Source("postgres", "public", name);
    return new SemanticModel.SemanticObject(
        1,
        "object",
        metadata,
        new SemanticModel.ObjectSpec(source, primaryKey, dimensions, relationships),
        "objects/" + name + ".yaml");
  }

  private SemanticModel.Dimension dimension(String name) {
    return new SemanticModel.Dimension(name, name, name, "bigint", name, false);
  }

  private SemanticModel.Relationship relationship(
      String name,
      String target,
      List<String> sourceFields,
      List<String> targetFields,
      SemanticModel.Cardinality cardinality) {
    return new SemanticModel.Relationship(
        name,
        target,
        sourceFields,
        targetFields,
        cardinality,
        SemanticModel.JoinType.left);
  }
}
