package com.acme.semantic.pgwire;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PgQueryServiceRoutingTest {
  private final PgQueryService service = new PgQueryService(null, null, null);

  @Test
  void routesCatalogTablesUsingParsedTableReferences() {
    assertThat(service.isSystem("SELECT * FROM pg_catalog.pg_type")).isTrue();
    assertThat(service.isSystem("SELECT * FROM information_schema.tables")).isTrue();
    assertThat(service.isSystem("SELECT current_schema(), session_user")).isTrue();
  }

  @Test
  void doesNotRouteSemanticQueriesByStringLiteralContent() {
    assertThat(
            service.isSystem(
                "SELECT customer_id FROM retail.orders WHERE status = 'pg_catalog'"))
        .isFalse();
    assertThat(
            service.isSystem(
                "SELECT customer_id FROM retail.orders WHERE status = 'information_schema'"))
        .isFalse();
  }
}
