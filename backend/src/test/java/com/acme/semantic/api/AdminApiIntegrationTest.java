package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:phase-b-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.api-key=phase-b-bootstrap-key",
      "semantic.allow-insecure-api-key=false",
      "semantic.pgwire.enabled=false",
      "semantic.mcp.enabled=false",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "witness.auth.jwt-secret=0123456789abcdef0123456789abcdef",
      "witness.auth.allow-default-admin=true"
    })
@AutoConfigureMockMvc
class AdminApiIntegrationTest {
  private static final String PASSWORD = "password-123";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;

  private String adminBearer;
  private String restrictedBearer;
  private long adminUser;
  private long adminRole;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update("UPDATE refresh_tokens SET replaced_by=NULL");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM service_accounts WHERE name<>'semantic-api-key'");
    jdbc.update("DELETE FROM users WHERE username<>'admin'");
    jdbc.update("DELETE FROM roles WHERE name NOT IN ('admin','bootstrap-service-account')");
    jdbc.update(
        "UPDATE users SET password_hash=?,enabled=TRUE,must_change_password=TRUE WHERE username='admin'",
        passwords.encode("admin"));
    jdbc.update("UPDATE roles SET is_admin=TRUE WHERE name='admin'");
    adminUser = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", Long.class);
    adminRole = jdbc.queryForObject("SELECT id FROM roles WHERE name='admin'", Long.class);

    long restrictedRole = role("phase-b-restricted", false);
    grant(restrictedRole, "retail", "READ");
    long restricted = user("phase-b-user", true, restrictedRole);
    assertThat(restricted).isPositive();
    adminBearer = bearer(login("admin", "admin"));
    restrictedBearer = bearer(login("phase-b-user", PASSWORD));
  }

  static Stream<AdminRequest> adminEndpoints() {
    return Stream.of(
        new AdminRequest("GET users", get("/api/v1/admin/users")),
        new AdminRequest("GET user", get("/api/v1/admin/users/1")),
        new AdminRequest("POST user", json(post("/api/v1/admin/users"), Map.of())),
        new AdminRequest("PUT user", json(put("/api/v1/admin/users/1"), Map.of())),
        new AdminRequest("DELETE user", delete("/api/v1/admin/users/1")),
        new AdminRequest("PUT user roles", json(put("/api/v1/admin/users/1/roles"), Map.of())),
        new AdminRequest("POST user password", json(post("/api/v1/admin/users/1/password"), Map.of())),
        new AdminRequest("GET roles", get("/api/v1/admin/roles")),
        new AdminRequest("GET role", get("/api/v1/admin/roles/1")),
        new AdminRequest("POST role", json(post("/api/v1/admin/roles"), Map.of())),
        new AdminRequest("PUT role", json(put("/api/v1/admin/roles/1"), Map.of())),
        new AdminRequest("DELETE role", delete("/api/v1/admin/roles/1")),
        new AdminRequest("PUT grants", json(put("/api/v1/admin/roles/1/grants"), Map.of())),
        new AdminRequest(
            "PUT capabilities", json(put("/api/v1/admin/roles/1/capabilities"), Map.of())),
        new AdminRequest("GET accounts", get("/api/v1/admin/service-accounts")),
        new AdminRequest("GET account", get("/api/v1/admin/service-accounts/1")),
        new AdminRequest("POST account", json(post("/api/v1/admin/service-accounts"), Map.of())),
        new AdminRequest("PUT account", json(put("/api/v1/admin/service-accounts/1"), Map.of())),
        new AdminRequest("POST rotate", post("/api/v1/admin/service-accounts/1/rotate")),
        new AdminRequest("DELETE account", delete("/api/v1/admin/service-accounts/1")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adminEndpoints")
  void everyAdminEndpointRequiresAnAdministrator(AdminRequest endpoint) throws Exception {
    mvc.perform(endpoint.request()).andExpect(status().isUnauthorized());
    mvc.perform(endpoint.request().header("Authorization", restrictedBearer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void malformedAndMissingAdminPathIdsReturnStandardClientErrors() throws Exception {
    mvc.perform(get("/api/v1/admin/users/abc").header("Authorization", adminBearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Invalid or missing path parameter"));
    mvc.perform(
            json(put("/api/v1/admin/users//roles"), Map.of("roleIds", List.of()))
                .header("Authorization", adminBearer))
        .andExpect(status().is4xxClientError())
        .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.lessThan(500)))
        .andExpect(jsonPath("$.code").exists());
  }

  @Test
  void rejectsEveryWayToRemoveTheLastEnabledAdministrator() throws Exception {
    conflict(delete("/api/v1/admin/users/" + adminUser), "last enabled administrator");
    conflict(
        json(
            put("/api/v1/admin/users/" + adminUser),
            Map.of("displayName", "Admin", "email", "admin@example.test", "enabled", false)),
        "last enabled administrator");
    conflict(
        json(put("/api/v1/admin/users/" + adminUser + "/roles"), Map.of("roleIds", List.of())),
        "last enabled administrator");
    conflict(
        json(
            put("/api/v1/admin/roles/" + adminRole),
            Map.of("description", "Administrator", "admin", false)),
        "last role");
    conflict(delete("/api/v1/admin/roles/" + adminRole), "last role");

    assertThat(login("admin", "admin").path("user").path("admin").asBoolean()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT u.id) FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.enabled=TRUE AND r.is_admin=TRUE",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void provisionsRestrictedUserAndRoleForDirectUiConsumption() throws Exception {
    JsonNode role =
        admin(
            json(
                post("/api/v1/admin/roles"),
                Map.of("name", "retail-analyst", "description", "Retail analyst", "admin", false)),
            201);
    long roleId = role.path("id").asLong();
    JsonNode grants =
        admin(
            json(
                put("/api/v1/admin/roles/" + roleId + "/grants"),
                Map.of(
                    "grants",
                    List.of(Map.of("domain", "retail", "permissions", List.of("READ", "QUERY"))))),
            200);
    assertThat(grants.path("grants").get(0).path("permissions").size()).isEqualTo(2);

    JsonNode user =
        admin(
            json(
                post("/api/v1/admin/users"),
                Map.of(
                    "username", "alice-b",
                    "password", PASSWORD,
                    "displayName", "Alice",
                    "email", "alice@example.test")),
            201);
    long userId = user.path("id").asLong();
    admin(
        json(
            put("/api/v1/admin/users/" + userId + "/roles"), Map.of("roleIds", List.of(roleId))),
        200);
    String alice = bearer(login("alice-b", PASSWORD));

    mvc.perform(get("/api/v1/objects").header("Authorization", alice))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].metadata.domain")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("retail"))));
    mvc.perform(get("/api/v1/objects/ai_rnd.experiments").header("Authorization", alice))
        .andExpect(status().isNotFound());
  }

  @Test
  void serviceAccountKeyIsReturnedOnceAndRotationInvalidatesTheOldKey() throws Exception {
    long roleId = role("phase-b-service-role", false);
    grant(roleId, "retail", "READ");
    JsonNode created =
        admin(
            json(
                post("/api/v1/admin/service-accounts"),
                Map.of("name", "phase-b-agent", "roleId", roleId)),
            201);
    long id = created.path("serviceAccount").path("id").asLong();
    String first = created.path("apiKey").asText();
    assertThat(first).isNotBlank();
    JsonNode fetched =
        admin(get("/api/v1/admin/service-accounts/" + id), 200);
    assertThat(fetched.has("apiKey")).isFalse();
    mvc.perform(get("/api/v1/objects").header("X-API-Key", first)).andExpect(status().isOk());

    JsonNode rotated =
        admin(post("/api/v1/admin/service-accounts/" + id + "/rotate"), 200);
    String replacement = rotated.path("apiKey").asText();
    assertThat(replacement).isNotEqualTo(first);
    mvc.perform(get("/api/v1/objects").header("X-API-Key", first))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/objects").header("X-API-Key", replacement))
        .andExpect(status().isOk());
  }

  private void conflict(MockHttpServletRequestBuilder request, String reason) throws Exception {
    mvc.perform(request.header("Authorization", adminBearer))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(reason)));
  }

  private JsonNode admin(MockHttpServletRequestBuilder request, int expected) throws Exception {
    String response =
        mvc.perform(request.header("Authorization", adminBearer))
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
  }

  private JsonNode login(String username, String password) throws Exception {
    String response =
        mvc.perform(
                json(
                    post("/api/v1/auth/login"),
                    Map.of("username", username, "password", password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(response);
  }

  private String bearer(JsonNode token) {
    return "Bearer " + token.path("accessToken").asText();
  }

  private long role(String name, boolean admin) {
    jdbc.update("INSERT INTO roles(name,description,is_admin) VALUES (?,?,?)", name, name, admin);
    return jdbc.queryForObject("SELECT id FROM roles WHERE name=?", Long.class, name);
  }

  private long user(String username, boolean enabled, long roleId) {
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO users(username,password_hash,provider,display_name,enabled,must_change_password,created_at,updated_at) VALUES (?,?,?,?,?,FALSE,?,?)",
        username,
        passwords.encode(PASSWORD),
        "local",
        username,
        enabled,
        now,
        now);
    long id = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", id, roleId);
    return id;
  }

  private void grant(long roleId, String domain, String permission) {
    jdbc.update(
        "INSERT INTO role_domain_grants(role_id,domain,permission) VALUES (?,?,?)",
        roleId,
        domain,
        permission);
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, Object body) {
    try {
      return request
          .contentType(MediaType.APPLICATION_JSON)
          .content(new ObjectMapper().writeValueAsString(body));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  record AdminRequest(String label, MockHttpServletRequestBuilder request) {
    @Override
    public String toString() {
      return label;
    }
  }
}
