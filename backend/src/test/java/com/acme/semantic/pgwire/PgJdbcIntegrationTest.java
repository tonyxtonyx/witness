package com.acme.semantic.pgwire;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.semantic.cache.SemanticCacheManager;
import com.acme.semantic.execution.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(
    properties = {
      "semantic.pgwire.port=55434",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.api-key=secret",
      "semantic.allow-insecure-api-key=true",
      "spring.datasource.url=jdbc:h2:mem:pgwire-compat;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.trino.url=jdbc:trino://invalid:8080"
    })
class PgJdbcIntegrationTest {
  private static final String USER = "semantic-api-key";
  private static final String PASSWORD = "secret";
  @MockBean QueryExecutor executor;
  @Autowired PgQueryService queryService;
  @Autowired SemanticCacheManager cache;

  @BeforeEach
  void stub() {
    cache.invalidateAll();
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
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD)) {
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
        assertThat(rs.getString(2)).isEqualTo(USER);
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

  @Test
  void exposesSemanticRelationshipsAsVirtualForeignKeys() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD)) {
      DatabaseMetaData metadata = connection.getMetaData();

      try (ResultSet keys = metadata.getPrimaryKeys("semantic", "retail", "orders")) {
        assertThat(read(keys, "COLUMN_NAME")).containsExactly("order_id");
      }

      try (ResultSet keys = metadata.getImportedKeys("semantic", "retail", "orders")) {
        List<String> relationships = new ArrayList<>();
        while (keys.next()) {
          relationships.add(keys.getString("FK_NAME"));
          assertThat(keys.getString("FKTABLE_SCHEM")).isEqualTo("retail");
          assertThat(keys.getString("FKTABLE_NAME")).isEqualTo("orders");
          assertThat(keys.getString("PKTABLE_SCHEM")).isEqualTo("retail");
          assertThat(keys.getShort("UPDATE_RULE"))
              .isEqualTo((short) DatabaseMetaData.importedKeyNoAction);
          assertThat(keys.getShort("DELETE_RULE"))
              .isEqualTo((short) DatabaseMetaData.importedKeyNoAction);
          assertThat(keys.getShort("DEFERRABILITY"))
              .isEqualTo((short) DatabaseMetaData.importedKeyNotDeferrable);
        }
        assertThat(relationships).containsExactly("order_customer", "order_product");
      }

      try (ResultSet keys = metadata.getExportedKeys("semantic", "retail", "customers")) {
        assertThat(read(keys, "FK_NAME")).containsExactly("order_customer");
      }

      try (ResultSet keys =
          metadata.getCrossReference(
              "semantic", "retail", "customers", "semantic", "retail", "orders")) {
        assertThat(read(keys, "FK_NAME")).containsExactly("order_customer");
      }

      try (Statement statement = connection.createStatement();
          ResultSet constraints =
              statement.executeQuery("SELECT * FROM information_schema.table_constraints")) {
        List<String> foreignKeys = new ArrayList<>();
        while (constraints.next())
          if ("FOREIGN KEY".equals(constraints.getString("constraint_type"))) {
            foreignKeys.add(constraints.getString("constraint_name"));
            assertThat(constraints.getString("enforced")).isEqualTo("NO");
          }
        assertThat(foreignKeys).contains("order_customer", "order_product");
      }

      try (Statement statement = connection.createStatement();
          ResultSet columns =
              statement.executeQuery("SELECT * FROM information_schema.key_column_usage")) {
        assertThat(read(columns, "constraint_name"))
            .contains("orders_pkey", "order_customer", "order_product");
      }

      try (Statement statement = connection.createStatement();
          ResultSet references =
              statement.executeQuery(
                  "SELECT * FROM information_schema.referential_constraints")) {
        assertThat(read(references, "constraint_name"))
            .contains("order_customer", "order_product");
      }
    }
  }

  @Test
  void keepsDbeaverTableAndColumnLookupsScopedToTheirSchema() throws Exception {
    for (String queryMode : List.of("extended", "simple")) {
      try (Connection connection =
          DriverManager.getConnection(
              "jdbc:postgresql://localhost:55434/semantic?sslmode=disable&preferQueryMode="
                  + queryMode,
              USER,
              PASSWORD)) {
        Map<String, Long> schemas = new HashMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet result =
                statement.executeQuery(
                    "SELECT n.oid,n.*,d.description FROM pg_catalog.pg_namespace n "
                        + "LEFT JOIN pg_catalog.pg_description d ON d.objoid=n.oid "
                        + "ORDER BY nspname")) {
          while (result.next()) schemas.put(result.getString("nspname"), result.getLong("oid"));
        }

        Map<String, Long> retail = dbeaverTables(connection, schemas.get("retail"));
        Map<String, Long> aiRnd = dbeaverTables(connection, schemas.get("ai_rnd"));

        assertThat(retail.keySet())
            .contains("customers", "orders", "products")
            .doesNotContain("experiments");
        assertThat(aiRnd.keySet()).containsExactly("experiments");

        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT c.relname,a.*,dsc.description FROM pg_catalog.pg_attribute a "
                    + "INNER JOIN pg_catalog.pg_class c ON a.attrelid=c.oid "
                    + "LEFT JOIN pg_catalog.pg_description dsc ON dsc.objoid=c.oid "
                    + "WHERE a.attnum>0 AND c.oid=?")) {
          statement.setLong(1, aiRnd.get("experiments"));
          try (ResultSet result = statement.executeQuery()) {
            List<String> relations = read(result, "relname");
            assertThat(relations).isNotEmpty().containsOnly("experiments");
          }
        }
      }
    }
  }

  @Test
  void keepsDbeaverDescribeAndPgClassShapeProbesEmpty() throws Exception {
    PgQueryService.Prepared prepared =
        queryService.prepare(
            "SELECT c.oid,c.* FROM pg_catalog.pg_class c WHERE c.relnamespace=$1");

    assertThat(prepared.staticResult().rows()).isEmpty();

    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT reltype FROM pg_catalog.pg_class WHERE 1<>1 LIMIT 1")) {
      assertThat(result.getMetaData().getColumnCount()).isEqualTo(1);
      assertThat(result.getMetaData().getColumnName(1)).isEqualTo("reltype");
      assertThat(result.next()).isFalse();
    }
  }

  @Test
  void staysSynchronizedForIsValidAndBinaryPreparedStatements() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable"
                + "&prepareThreshold=1&binaryTransfer=true",
            USER,
            PASSWORD)) {
      assertThat(connection.isValid(2)).isTrue();
      assertThat(connection.isValid(2)).isTrue();

      try (PreparedStatement statement = connection.prepareStatement("SELECT 1 AS value")) {
        for (int i = 0; i < 8; i++) {
          try (ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("value")).isEqualTo(1);
          }
        }
      }

      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT customer_id FROM retail.orders WHERE customer_id = ?")) {
        statement.setLong(1, 1L);
        for (int i = 0; i < 8; i++) {
          try (ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1L);
          }
        }
      }
    }
  }

  @Test
  void tracksTransactionsRecoversWithRollbackAndHonorsSetSchema() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD)) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("SELECT 1")) {
        assertThat(result.next()).isTrue();
      }
      assertThatThrownBy(
              () -> {
                try (Statement statement = connection.createStatement()) {
                  statement.executeQuery("SELECT unknown_column FROM retail.orders");
                }
              })
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () -> {
                try (Statement statement = connection.createStatement()) {
                  statement.executeQuery("SELECT 1");
                }
              })
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("aborted");

      connection.rollback();
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("SELECT 1")) {
        assertThat(result.next()).isTrue();
      }
      connection.rollback();
      connection.setAutoCommit(true);

      connection.setSchema("ai_rnd");
      assertThat(connection.getSchema()).isEqualTo("ai_rnd");
    }
  }

  @Test
  void exposesScopedColumnsAndPostgresTypeMetadata() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD)) {
      try (ResultSet columns =
          connection.getMetaData().getColumns("semantic", "ai_rnd", "experiments", "%")) {
        List<String> tables = read(columns, "TABLE_NAME");
        assertThat(tables).isNotEmpty().containsOnly("experiments");
      }
      try (ResultSet types = connection.getMetaData().getTypeInfo()) {
        assertThat(types.next()).isTrue();
        assertThat(types.getString("TYPE_NAME")).isNotBlank();
      }
    }
  }

  @Test
  void executesCommentsAndMultipleStatementsInSimpleMode() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:55434/semantic?sslmode=disable&preferQueryMode=simple",
                USER,
                PASSWORD);
        Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery("SELECT 1 -- health probe")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(1);
      }
      assertThat(statement.execute("SELECT 1; SELECT 2")).isTrue();
      try (ResultSet first = statement.getResultSet()) {
        assertThat(first.next()).isTrue();
        assertThat(first.getInt(1)).isEqualTo(1);
      }
      assertThat(statement.getMoreResults()).isTrue();
      try (ResultSet second = statement.getResultSet()) {
        assertThat(second.next()).isTrue();
        assertThat(second.getInt(1)).isEqualTo(2);
      }
      assertThat(statement.execute("-- comment-only query")).isFalse();
      try (ResultSet constants =
          statement.executeQuery("SELECT 1 AS n, 'Mixed Case' AS label, NULL AS missing")) {
        assertThat(constants.next()).isTrue();
        assertThat(constants.getInt("n")).isEqualTo(1);
        assertThat(constants.getString("label")).isEqualTo("Mixed Case");
        assertThat(constants.getObject("missing")).isNull();
      }
    }
  }

  @Test
  void keepsCommonDatabaseMetadataMethodsUsable() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD)) {
      DatabaseMetaData metadata = connection.getMetaData();
      assertThatCode(() -> drain(metadata.getCatalogs())).doesNotThrowAnyException();
      assertThatCode(() -> drain(metadata.getFunctions(null, "%", "%")))
          .doesNotThrowAnyException();
      assertThatCode(
              () -> drain(metadata.getIndexInfo("semantic", "retail", "orders", false, false)))
          .doesNotThrowAnyException();
      assertThatCode(
              () ->
                  drain(
                      metadata.getBestRowIdentifier(
                          "semantic",
                          "retail",
                          "orders",
                          DatabaseMetaData.bestRowSession,
                          true)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void cancelsAnExecutingQueryAndKeepsTheConnectionUsable() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    when(executor.execute(any(), anyList()))
        .thenAnswer(
            ignored -> {
              started.countDown();
              try {
                Thread.sleep(30_000);
                throw new AssertionError("Query was not cancelled");
              } catch (InterruptedException interrupted) {
                throw new QueryExecutionException(
                    "57014", "Query cancelled", interrupted);
              }
            });

    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD);
        Statement statement = connection.createStatement();
        ExecutorService client = Executors.newSingleThreadExecutor()) {
      Future<?> query =
          client.submit(
              () -> {
                try {
                  statement.executeQuery("SELECT customer_id FROM retail.orders");
                  throw new AssertionError("Cancelled query unexpectedly succeeded");
                } catch (SQLException exception) {
                  assertThat(exception.getSQLState()).isEqualTo("57014");
                }
              });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      statement.cancel();
      query.get(3, TimeUnit.SECONDS);
      assertThat(connection.isValid(2)).isTrue();
    }
  }

  @Test
  void projectsAndFiltersSimpleCatalogQueriesInsteadOfReturningFixedShapes() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:55434/semantic?sslmode=disable", USER, PASSWORD)) {
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT n.nspname, c.relname FROM pg_catalog.pg_class c "
                      + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                      + "WHERE n.nspname = 'retail' ORDER BY c.relname")) {
        assertThat(result.getMetaData().getColumnCount()).isEqualTo(2);
        List<String> relations = new ArrayList<>();
        while (result.next()) {
          assertThat(result.getString("nspname")).isEqualTo("retail");
          relations.add(result.getString("relname"));
        }
        assertThat(relations)
            .contains("customers", "orders", "products")
            .doesNotContain("experiments");
      }

      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT n.nspname FROM pg_catalog.pg_namespace n "
                      + "WHERE n.nspname = 'ai_rnd'")) {
        assertThat(result.getMetaData().getColumnCount()).isEqualTo(1);
        assertThat(read(result, "nspname")).containsExactly("ai_rnd");
      }
    }
  }

  private Map<String, Long> dbeaverTables(Connection connection, long schemaOid)
      throws SQLException {
    Map<String, Long> tables = new LinkedHashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT c.oid,c.*,d.description,"
                + "pg_catalog.pg_get_expr(c.relpartbound,c.oid) AS partition_expr,"
                + "pg_catalog.pg_get_partkeydef(c.oid) AS partition_key "
                + "FROM pg_catalog.pg_class c "
                + "LEFT JOIN pg_catalog.pg_description d ON d.objoid=c.oid "
                + "WHERE c.relnamespace=? AND c.relkind NOT IN ('i','I','c')")) {
      statement.setLong(1, schemaOid);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) tables.put(result.getString("relname"), result.getLong("oid"));
      }
    }
    return tables;
  }

  private List<String> read(ResultSet resultSet, String column) throws SQLException {
    List<String> values = new ArrayList<>();
    while (resultSet.next()) values.add(resultSet.getString(column));
    return values;
  }

  private void drain(ResultSet resultSet) throws SQLException {
    try (resultSet) {
      while (resultSet.next()) {
        // Consume the driver-generated result to exercise column lookup and conversion.
      }
    }
  }
}
