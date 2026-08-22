package com.acme.semantic.pgwire;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.semantic.auth.AdminIdentityService;
import com.acme.semantic.auth.IdentityRepository;
import com.acme.semantic.cache.SemanticCacheManager;
import com.acme.semantic.core.SemanticPermission;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:pgwire-rbac;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.pgwire.port=55435",
      "semantic.gitlab.enabled=false",
      "semantic.mcp.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.trino.url=jdbc:trino://invalid:8080",
      "witness.auth.jwt-secret=0123456789abcdef0123456789abcdef",
      "witness.auth.allow-default-admin=true"
    })
class PgWireRbacIntegrationTest {
  private static final String URL =
      "jdbc:postgresql://localhost:55435/semantic?sslmode=disable";
  private static final String PASSWORD = "pgwire-password";

  @Autowired AdminIdentityService admin;
  @Autowired IdentityRepository identities;
  @Autowired JdbcTemplate jdbc;
  @Autowired SemanticCacheManager cache;
  @MockBean QueryExecutor executor;

  private SemanticPrincipal administrator;

  @BeforeEach
  void setUp() {
    cache.invalidateAll();
    jdbc.update(
        "DELETE FROM service_accounts WHERE name LIKE 'pgwire-%'");
    jdbc.update(
        "DELETE FROM users WHERE username LIKE 'pgwire-%'");
    jdbc.update(
        "DELETE FROM roles WHERE name LIKE 'pgwire-%'");
    long adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", Long.class);
    administrator = identities.resolveUser(adminId).orElseThrow();
    when(executor.execute(any(), anyList()))
        .thenReturn(
            new QueryResult(
                List.of(new QueryResult.Column("customer_id", Types.BIGINT, "bigint", false)),
                List.of(List.of(1L)),
                "rbac-query"));
  }

  @Test
  void authenticatesUsersAndServiceAccountsWithoutCredentialOracles() throws Exception {
    String name = "pgwire-auth";
    AdminIdentityService.RoleView role = role("pgwire-auth-role", Map.of());
    AdminIdentityService.UserView user = user(name, role.id());
    long refreshTokens = jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Long.class);

    try (Connection ignored = connect(name, PASSWORD)) {}
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Long.class))
        .isEqualTo(refreshTokens);

    List<SQLException> failures = new ArrayList<>();
    failures.add(connectionFailure(URL, name, "wrong-password"));

    user = admin.updateUser(administrator, user.id(), user.displayName(), user.email(), false);
    failures.add(connectionFailure(URL, name, PASSWORD));
    admin.deleteUser(administrator, user.id());
    failures.add(connectionFailure(URL, name, PASSWORD));

    AdminIdentityService.ServiceAccountSecret account =
        admin.createServiceAccount(administrator, name, role.id());
    try (Connection ignored = connect(name, account.apiKey())) {}
    failures.add(connectionFailure(URL, name, "wrong-api-key"));
    AdminIdentityService.ServiceAccountSecret rotated =
        admin.rotateServiceAccount(administrator, account.serviceAccount().id());
    failures.add(connectionFailure(URL, name, account.apiKey()));
    try (Connection ignored = connect(name, rotated.apiKey())) {}
    admin.deleteServiceAccount(administrator, account.serviceAccount().id());
    failures.add(connectionFailure(URL, name, rotated.apiKey()));

    assertThat(failures).extracting(SQLException::getSQLState).containsOnly("28P01");
    assertThat(failures)
        .extracting(SQLException::getMessage)
        .containsOnly(failures.getFirst().getMessage());
    assertThat(failures.getFirst().getMessage())
        .contains("password authentication failed for user \"" + name + "\"");
  }

  @Test
  void filtersEveryJdbcAndInformationSchemaMetadataSurface() throws Exception {
    AdminIdentityService.RoleView role =
        role(
            "pgwire-retail-role",
            Map.of("retail", Set.of(SemanticPermission.READ, SemanticPermission.QUERY)));
    user("pgwire-retail", role.id());

    try (Connection connection = connect("pgwire-retail", PASSWORD)) {
      DatabaseMetaData metadata = connection.getMetaData();
      assertThat(values(metadata.getSchemas(), "TABLE_SCHEM"))
          .containsExactly("retail")
          .doesNotContain("ai_rnd");
      assertThat(values(metadata.getTables("semantic", "%", "%", new String[] {"TABLE"}), "TABLE_SCHEM"))
          .isNotEmpty()
          .containsOnly("retail");
      assertThat(values(metadata.getColumns("semantic", "%", "%", "%"), "TABLE_SCHEM"))
          .isNotEmpty()
          .containsOnly("retail");
      assertThat(values(metadata.getPrimaryKeys("semantic", "retail", "orders"), "TABLE_SCHEM"))
          .containsExactly("retail");
      assertThat(values(metadata.getPrimaryKeys("semantic", "ai_rnd", "experiments"), "TABLE_SCHEM"))
          .isEmpty();
      assertThat(values(metadata.getImportedKeys("semantic", "retail", "orders"), "FKTABLE_SCHEM"))
          .isNotEmpty()
          .containsOnly("retail");

      assertOnlyDomain(connection, "SELECT * FROM information_schema.tables", "TABLE_SCHEM");
      assertOnlyDomain(connection, "SELECT * FROM information_schema.columns", "TABLE_SCHEM");
      assertOnlyDomain(connection, "SELECT * FROM information_schema.schemata", "schema_name");
      assertOnlyDomain(
          connection, "SELECT * FROM information_schema.table_constraints", "table_schema");
      assertOnlyDomain(
          connection, "SELECT * FROM information_schema.key_column_usage", "table_schema");
      assertOnlyDomain(
          connection,
          "SELECT * FROM information_schema.referential_constraints",
          "constraint_schema");
      assertOnlyDomain(
          connection,
          "SELECT n.nspname, c.relname FROM pg_catalog.pg_class c "
              + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace",
          "nspname");
      assertOnlyDomain(
          connection,
          "SELECT n.nspname, c.relname, a.attname FROM pg_catalog.pg_attribute a "
              + "JOIN pg_catalog.pg_class c ON c.oid=a.attrelid "
              + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace",
          "nspname");
      assertThat(queryValues(connection, "SELECT c.* FROM pg_catalog.pg_class c", "relname"))
          .isNotEmpty()
          .doesNotContain("experiments");
      assertThat(
              queryValues(
                  connection,
                  "SELECT c.relname,a.* FROM pg_catalog.pg_attribute a "
                      + "JOIN pg_catalog.pg_class c ON a.attrelid=c.oid",
                  "relname"))
          .isNotEmpty()
          .doesNotContain("experiments");
      assertThat(
              values(
                  metadata.getBestRowIdentifier(
                      "semantic",
                      "ai_rnd",
                      "experiments",
                      DatabaseMetaData.bestRowSession,
                      true),
                  "COLUMN_NAME"))
          .isEmpty();

      assertThat(values(metadata.getTables("semantic", "ai_rnd", "experiments", null), "TABLE_NAME"))
          .isEmpty();
      SQLException hidden = queryFailure(connection, "SELECT quality_score FROM ai_rnd.experiments");
      SQLException fabricated =
          queryFailure(connection, "SELECT quality_score FROM ai_rnd.does_not_exist");
      assertThat(hidden.getSQLState()).isEqualTo(fabricated.getSQLState()).isEqualTo("42P01");
      assertThat(hidden.getMessage()).isEqualTo(fabricated.getMessage());

      SQLException hiddenSchema = setSchemaFailure(connection, "ai_rnd");
      SQLException fabricatedSchema = setSchemaFailure(connection, "does_not_exist");
      assertThat(hiddenSchema.getSQLState()).isEqualTo(fabricatedSchema.getSQLState()).isEqualTo("3F000");
      assertThat(hiddenSchema.getMessage()).isEqualTo(fabricatedSchema.getMessage());
    }
  }

  @Test
  void appliesGrantRevocationAndUserDisablementOnTheSameSession() throws Exception {
    AdminIdentityService.RoleView role =
        role(
            "pgwire-revoke-role",
            Map.of("retail", Set.of(SemanticPermission.READ, SemanticPermission.QUERY)));
    AdminIdentityService.UserView user = user("pgwire-revoke", role.id());

    try (Connection connection = connect("pgwire-revoke", PASSWORD)) {
      query(connection, "SELECT customer_id FROM retail.orders");
      admin.setRoleGrants(administrator, role.id(), List.of());
      assertThat(connection.getSchema()).isNull();
      assertThat(queryFailure(connection, "SELECT customer_id FROM retail.orders").getSQLState())
          .isEqualTo("42P01");

      admin.setRoleGrants(
          administrator,
          role.id(),
          List.of(
              new AdminIdentityService.DomainGrant(
                  "retail", Set.of(SemanticPermission.READ, SemanticPermission.QUERY))));
      query(connection, "SELECT customer_id FROM retail.orders");
      admin.updateUser(administrator, user.id(), user.displayName(), user.email(), false);
      assertThat(queryFailure(connection, "SELECT 1").getSQLState()).isEqualTo("28000");
      assertThatThrownBy(connection::createStatement).isInstanceOf(SQLException.class);
    }
  }

  @Test
  void distinguishesFatalConnectionErrorsFromUsableStatementErrorsOnTheWire()
      throws Exception {
    AdminIdentityService.RoleView readRole =
        role("pgwire-error-role", Map.of("retail", Set.of(SemanticPermission.READ)));
    user("pgwire-error", readRole.id());
    try (WireConnection wire = WireConnection.connect("pgwire-error", PASSWORD)) {
      PgError error = wire.queryError("SELECT customer_id FROM retail.orders");
      assertThat(error.severity()).isEqualTo("ERROR");
      assertThat(error.state()).isEqualTo("42501");
      assertThat(error.message()).isEqualTo("Query permission is required for every referenced domain");
      assertThat(wire.readMessage().type()).isEqualTo('Z');
    }

    AdminIdentityService.RoleView fatalRole = role("pgwire-fatal-role", Map.of());
    AdminIdentityService.UserView fatalUser = user("pgwire-fatal", fatalRole.id());
    try (WireConnection wire = WireConnection.connect("pgwire-fatal", PASSWORD)) {
      admin.updateUser(
          administrator,
          fatalUser.id(),
          fatalUser.displayName(),
          fatalUser.email(),
          false);
      PgError fatal = wire.queryError("SELECT 1");
      assertThat(fatal.severity()).isEqualTo("FATAL");
      assertThat(fatal.state()).isEqualTo("28000");
      assertThat(fatal.message()).isEqualTo("Session authorization is no longer valid");
      assertThat(wire.readMessage()).isNull();
    }

    try (WireConnection wire = WireConnection.startup("pgwire-malformed")) {
      wire.sendMalformedPassword();
      PgError malformed = wire.readError();
      assertThat(malformed.severity()).isEqualTo("FATAL");
      assertThat(malformed.state()).isEqualTo("08P01");
      assertThat(malformed.message()).isEqualTo("Unterminated PostgreSQL protocol string");
      assertThat(wire.readMessage()).isNull();
    }
  }

  @Test
  void revocationInsideTransactionAbortsUntilRollback() throws Exception {
    AdminIdentityService.RoleView role =
        role(
            "pgwire-transaction-role",
            Map.of("retail", Set.of(SemanticPermission.READ, SemanticPermission.QUERY)));
    user("pgwire-transaction", role.id());

    try (Connection connection = connect("pgwire-transaction", PASSWORD)) {
      connection.setAutoCommit(false);
      query(connection, "SELECT customer_id FROM retail.orders");
      admin.setRoleGrants(administrator, role.id(), List.of());
      assertThat(queryFailure(connection, "SELECT customer_id FROM retail.orders").getSQLState())
          .isEqualTo("42P01");
      assertThat(queryFailure(connection, "SELECT 1").getSQLState()).isEqualTo("25P02");
      connection.rollback();
      query(connection, "SELECT 1");
      connection.rollback();
    }
  }

  @Test
  void localUsernameMatchingIsCaseInsensitive() throws Exception {
    AdminIdentityService.RoleView role = role("pgwire-case-role", Map.of());
    user("dana", role.id());

    try (Connection connection = connect("DANA", PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT session_user")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getString(1)).isEqualTo("dana");
    }
  }

  @Test
  void revocationStopsAPreviouslyAuthorizedSuspendedPortal() throws Exception {
    AdminIdentityService.RoleView role =
        role(
            "pgwire-portal-role",
            Map.of("retail", Set.of(SemanticPermission.READ, SemanticPermission.QUERY)));
    user("pgwire-portal", role.id());
    when(executor.execute(any(), anyList()))
        .thenReturn(
            new QueryResult(
                List.of(new QueryResult.Column("customer_id", Types.BIGINT, "bigint", false)),
                List.of(List.of(1L), List.of(2L), List.of(3L)),
                "portal-query"));

    try (Connection connection = connect("pgwire-portal", PASSWORD)) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.setFetchSize(1);
        try (ResultSet result =
            statement.executeQuery("SELECT customer_id FROM retail.orders")) {
          assertThat(result.next()).isTrue();
          assertThat(result.getLong(1)).isEqualTo(1L);
          admin.setRoleGrants(administrator, role.id(), List.of());
          SQLException revoked = catchThrowableOfType(result::next, SQLException.class);
          assertThat(revoked.getSQLState()).isEqualTo("42P01");
        }
      }
    }
  }

  @Test
  void zeroGrantUserConnectsWithEmptyCatalogAndStaticMetadata() throws Exception {
    AdminIdentityService.RoleView role = role("pgwire-zero-role", Map.of());
    user("pgwire-zero", role.id());

    try (Connection connection = connect("pgwire-zero", PASSWORD)) {
      assertThat(values(connection.getMetaData().getSchemas(), "TABLE_SCHEM")).isEmpty();
      assertThat(values(connection.getMetaData().getTables(null, "%", "%", null), "TABLE_NAME"))
          .isEmpty();
      assertThat(values(connection.getMetaData().getColumns(null, "%", "%", "%"), "TABLE_NAME"))
          .isEmpty();
      try (ResultSet types = connection.getMetaData().getTypeInfo()) {
        assertThat(types.next()).isTrue();
      }
    }
  }

  @Test
  void readWithoutQueryCanBrowseButCannotExecute() throws Exception {
    AdminIdentityService.RoleView role =
        role("pgwire-read-role", Map.of("retail", Set.of(SemanticPermission.READ)));
    user("pgwire-read", role.id());

    try (Connection connection = connect("pgwire-read", PASSWORD)) {
      assertThat(values(
              connection.getMetaData().getTables("semantic", "retail", "orders", null),
              "TABLE_NAME"))
          .containsExactly("orders");
      assertThat(queryFailure(connection, "SELECT customer_id FROM retail.orders").getSQLState())
          .isEqualTo("42501");
    }
  }

  @Test
  void hardcodedDatabaseValidationIsIndependentOfAccountExistence() {
    AdminIdentityService.RoleView role = role("pgwire-database-role", Map.of());
    user("pgwire-database", role.id());
    String wrongDatabase = "jdbc:postgresql://localhost:55435/not_semantic?sslmode=disable";

    SQLException known = connectionFailure(wrongDatabase, "pgwire-database", PASSWORD);
    SQLException unknown = connectionFailure(wrongDatabase, "pgwire-unknown", PASSWORD);
    assertThat(known.getSQLState()).isEqualTo(unknown.getSQLState()).isEqualTo("3D000");
    assertThat(known.getMessage()).isEqualTo(unknown.getMessage());
  }

  private AdminIdentityService.RoleView role(
      String name, Map<String, Set<SemanticPermission>> grants) {
    AdminIdentityService.RoleView role = admin.createRole(administrator, name, "pgwire test", false);
    List<AdminIdentityService.DomainGrant> values =
        grants.entrySet().stream()
            .map(entry -> new AdminIdentityService.DomainGrant(entry.getKey(), entry.getValue()))
            .toList();
    return admin.setRoleGrants(administrator, role.id(), values);
  }

  private AdminIdentityService.UserView user(String name, long role) {
    AdminIdentityService.UserView user =
        admin.createUser(administrator, name, PASSWORD, name, null);
    return admin.setUserRoles(administrator, user.id(), Set.of(role));
  }

  private Connection connect(String username, String password) throws SQLException {
    return DriverManager.getConnection(URL, username, password);
  }

  private SQLException connectionFailure(String url, String username, String password) {
    return catchThrowableOfType(
        () -> {
          try (Connection ignored = DriverManager.getConnection(url, username, password)) {}
        },
        SQLException.class);
  }

  private SQLException queryFailure(Connection connection, String sql) {
    return catchThrowableOfType(() -> query(connection, sql), SQLException.class);
  }

  private SQLException setSchemaFailure(Connection connection, String schema) {
    return catchThrowableOfType(() -> connection.setSchema(schema), SQLException.class);
  }

  private void query(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {}
    }
  }

  private List<String> values(ResultSet result, String column) throws SQLException {
    try (result) {
      List<String> values = new ArrayList<>();
      while (result.next()) values.add(result.getString(column));
      return values;
    }
  }

  private void assertOnlyDomain(Connection connection, String sql, String column) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
      assertThat(values(result, column)).isNotEmpty().containsOnly("retail");
    }
  }

  private List<String> queryValues(Connection connection, String sql, String column)
      throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
      return values(result, column);
    }
  }

  private record PgMessage(char type, byte[] body) {}

  private record PgError(String severity, String state, String message) {}

  private static final class WireConnection implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    private WireConnection(Socket socket) throws Exception {
      this.socket = socket;
      this.socket.setSoTimeout(2_000);
      this.input = new DataInputStream(socket.getInputStream());
      this.output = new DataOutputStream(socket.getOutputStream());
    }

    static WireConnection connect(String username, String password) throws Exception {
      WireConnection wire = startup(username);
      wire.send('p', password);
      while (true) {
        PgMessage message = wire.readMessage();
        assertThat(message).isNotNull();
        if (message.type() == 'E') fail("Raw pgwire authentication failed");
        if (message.type() == 'Z') return wire;
      }
    }

    static WireConnection startup(String username) throws Exception {
      WireConnection wire = new WireConnection(new Socket("localhost", 55435));
      ByteArrayOutputStream startupBytes = new ByteArrayOutputStream();
      DataOutputStream startup = new DataOutputStream(startupBytes);
      startup.writeInt(196608);
      cstring(startup, "user");
      cstring(startup, username);
      cstring(startup, "database");
      cstring(startup, "semantic");
      startup.writeByte(0);
      wire.output.writeInt(startupBytes.size() + 4);
      wire.output.write(startupBytes.toByteArray());
      wire.output.flush();

      PgMessage authentication = wire.readMessage();
      assertThat(authentication.type()).isEqualTo('R');
      assertThat(new DataInputStream(new ByteArrayInputStream(authentication.body())).readInt())
          .isEqualTo(3);
      return wire;
    }

    PgError queryError(String sql) throws Exception {
      send('Q', sql);
      return readError();
    }

    PgError readError() throws Exception {
      while (true) {
        PgMessage message = readMessage();
        assertThat(message).isNotNull();
        if (message.type() == 'E') return error(message.body());
      }
    }

    void sendMalformedPassword() throws Exception {
      output.writeByte('p');
      output.writeInt(5);
      output.writeByte('x');
      output.flush();
    }

    PgMessage readMessage() throws Exception {
      int type;
      try {
        type = input.read();
      } catch (EOFException ignored) {
        return null;
      }
      if (type < 0) return null;
      int length = input.readInt();
      return new PgMessage((char) type, input.readNBytes(length - 4));
    }

    private void send(char type, String value) throws Exception {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      output.writeByte(type);
      output.writeInt(bytes.length + 5);
      output.write(bytes);
      output.writeByte(0);
      output.flush();
    }

    private PgError error(byte[] body) throws Exception {
      DataInputStream fields = new DataInputStream(new ByteArrayInputStream(body));
      Map<Character, String> values = new HashMap<>();
      while (fields.available() > 0) {
        int field = fields.readUnsignedByte();
        if (field == 0) break;
        values.put((char) field, cstring(fields));
      }
      return new PgError(values.get('S'), values.get('C'), values.get('M'));
    }

    private static void cstring(DataOutputStream output, String value) throws Exception {
      output.write(value.getBytes(StandardCharsets.UTF_8));
      output.writeByte(0);
    }

    private static String cstring(DataInputStream input) throws Exception {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      int value;
      while ((value = input.readUnsignedByte()) != 0) bytes.write(value);
      return bytes.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
      socket.close();
    }
  }
}
