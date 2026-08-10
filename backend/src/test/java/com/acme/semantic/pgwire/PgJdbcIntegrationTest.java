package com.acme.semantic.pgwire;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.semantic.execution.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(
    properties = {
      "semantic.pgwire.port=55434",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.pgwire.username=test",
      "semantic.pgwire.password=secret",
      "semantic.trino.url=jdbc:trino://invalid:8080"
    })
class PgJdbcIntegrationTest {
  @MockBean QueryExecutor executor;

  @BeforeEach
  void stub() {
    when(executor.execute(any(), anyList()))
        .thenReturn(
            new QueryResult(
                List.of(new QueryResult.Column("customer_id", Types.BIGINT, "bigint", false)),
                List.of(List.of(1L), List.of(2L)),
                "test-query"));
  }

  @Test
  void connectsExecutesAndReadsMetadata() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", "test", "secret")) {
      try (ResultSet rs = c.getMetaData().getSchemas()) {
        Set<String> schemas = new HashSet<>();
        while (rs.next()) schemas.add(rs.getString("TABLE_SCHEM"));
        assertThat(schemas).contains("retail", "ai_rnd");
      }
      try (ResultSet rs =
          c.getMetaData().getTables("semantic", "retail", "%", new String[] {"TABLE"})) {
        Set<String> tables = new HashSet<>();
        while (rs.next())
          if ("retail".equals(rs.getString("TABLE_SCHEM"))) tables.add(rs.getString("TABLE_NAME"));
        assertThat(tables).contains("orders", "customers", "products");
      }
      long retailOid = 0;
      try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT n.oid,n.*,d.description FROM pg_catalog.pg_namespace n LEFT JOIN"
                      + " pg_catalog.pg_description d ON d.objoid=n.oid ORDER BY nspname");
          ResultSet rs = s.executeQuery()) {
        while (rs.next())
          if ("retail".equals(rs.getString("nspname"))) retailOid = rs.getLong("oid");
        assertThat(retailOid).isPositive();
      }
      try (PreparedStatement s =
          c.prepareStatement(
              "SELECT c.oid,c.*,d.description FROM pg_catalog.pg_class c LEFT JOIN"
                  + " pg_catalog.pg_description d ON d.objoid=c.oid WHERE c.relnamespace=?")) {
        s.setLong(1, retailOid);
        try (ResultSet rs = s.executeQuery()) {
          Set<String> tables = new HashSet<>();
          while (rs.next()) tables.add(rs.getString("relname"));
          assertThat(tables).contains("orders", "customers", "products");
        }
      }
      try (PreparedStatement s =
          c.prepareStatement(
              "SELECT c.relname,a.*,dsc.description FROM pg_catalog.pg_attribute a INNER JOIN"
                  + " pg_catalog.pg_class c ON a.attrelid=c.oid WHERE c.relnamespace=?")) {
        s.setLong(1, retailOid);
        try (ResultSet rs = s.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString("attname")).isNotBlank();
        }
      }
      try (PreparedStatement s = c.prepareStatement("SELECT current_schema(),session_user");
          ResultSet rs = s.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("retail");
        assertThat(rs.getString(2)).isEqualTo("semantic");
      }
      try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT t.oid,t.* FROM pg_catalog.pg_type t WHERE t.typname IS NOT NULL");
          ResultSet rs = s.executeQuery()) {
        assertThat(rs.getMetaData().getColumnCount()).isGreaterThan(5);
      }
      try (Statement s = c.createStatement();
          ResultSet rs = s.executeQuery("SELECT customer_id FROM retail.orders LIMIT 2")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(1L);
      }
      try (PreparedStatement s =
          c.prepareStatement("SELECT customer_id FROM semantic.orders WHERE customer_id = ?")) {
        s.setLong(1, 1);
        try (ResultSet rs = s.executeQuery()) {
          assertThat(rs.next()).isTrue();
        }
      }
    }
  }
}
