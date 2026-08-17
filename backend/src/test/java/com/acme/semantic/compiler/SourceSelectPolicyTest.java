package com.acme.semantic.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SourceSelectPolicyTest {
  private final SourceSelectPolicy policy = new SourceSelectPolicy();

  @Test
  void acceptsJoinedReadOnlySelectWithFullyQualifiedTables() {
    String rendered =
        policy.render(
            """
            SELECT o.order_id, o.amount, c.country
            FROM postgres.public.orders o
            LEFT JOIN postgres.public.customers c ON c.customer_id = o.customer_id
            """);

    assertThat(rendered)
        .contains("postgres.public.orders o")
        .contains("LEFT JOIN postgres.public.customers c");
  }

  @Test
  void rejectsWritesCommentsParametersAndUnqualifiedTables() {
    assertThatThrownBy(() -> policy.render("DELETE FROM postgres.public.orders"))
        .hasMessageContaining("read-only SELECT");
    assertThatThrownBy(
            () -> policy.render("SELECT * FROM postgres.public.orders -- hidden change"))
        .hasMessageContaining("Comments");
    assertThatThrownBy(() -> policy.render("SELECT * FROM postgres.public.orders WHERE id = ?"))
        .hasMessageContaining("Parameters");
    assertThatThrownBy(() -> policy.render("SELECT * FROM orders"))
        .hasMessageContaining("catalog.schema.table");
  }
}
