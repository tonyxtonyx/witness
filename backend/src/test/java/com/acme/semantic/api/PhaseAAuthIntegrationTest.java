package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.auth.IdentityRepository;
import com.acme.semantic.auth.JwtTokenService;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.compiler.SemanticSqlCompiler;
import com.acme.semantic.core.DefaultSemanticAccessPolicy;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticCapability;
import com.acme.semantic.core.SemanticPermission;
import com.acme.semantic.core.SemanticPrincipal;
import jakarta.servlet.http.Cookie;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:phase-a-auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.api-key=phase-a-service-key",
      "semantic.allow-insecure-api-key=false",
      "semantic.pgwire.enabled=false",
      "semantic.mcp.enabled=false",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.trino.url=jdbc:trino://invalid:8080",
      "semantic.mcp.expose-compiled-sql=true",
      "semantic.mcp.expose-physical-lineage=true",
      "witness.auth.jwt-secret=0123456789abcdef0123456789abcdef",
      "witness.auth.allow-default-admin=true",
      "witness.auth.access-token-minutes=60",
      "witness.auth.refresh-token-days=30"
    })
@AutoConfigureMockMvc
class PhaseAAuthIntegrationTest {
  private static final String PASSWORD = "password-123";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired IdentityRepository identities;
  @Autowired AuthenticationService authentication;
  @Autowired JwtTokenService jwt;
  @Autowired SemanticAccessPolicy policy;
  @Autowired SemanticCatalog catalog;
  @MockBean SemanticSqlCompiler compiler;
  @MockBean QueryExecutor executor;
  @MockBean ObjectCrudService objectCrud;
  @MockBean MetricCrudService metricCrud;

  private final Map<String, Credential> credentials = new LinkedHashMap<>();

  @BeforeEach
  void setUpIdentities() throws Exception {
    jdbc.update("UPDATE refresh_tokens SET replaced_by=NULL");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM users WHERE username<>'admin'");
    jdbc.update("DELETE FROM roles WHERE name NOT IN ('admin','bootstrap-service-account')");
    credentials.clear();

    long reader = role("domain-reader", false, Set.of(SemanticPermission.READ));
    long writer =
        role(
            "domain-writer",
            false,
            Set.of(SemanticPermission.READ, SemanticPermission.QUERY, SemanticPermission.WRITE));
    jdbc.update(
        "INSERT INTO role_capabilities(role_id,capability) VALUES (?,?)",
        writer,
        SemanticCapability.VIEW_COMPILED_SQL.name());
    long none = role("no-grant", false, Set.of());
    long disabled = role("disabled", false, Set.of(SemanticPermission.READ));
    long readerUser = user("reader", true, reader);
    long writerUser = user("writer", true, writer);
    long noGrantUser = user("nogrant", true, none);
    long disabledUser = user("disabled", false, disabled);

    credentials.put("admin", bearer(login("admin", "admin")));
    credentials.put("domain-reader", bearer(login("reader", PASSWORD)));
    credentials.put("domain-writer", bearer(login("writer", PASSWORD)));
    credentials.put("no-grant user", bearer(login("nogrant", PASSWORD)));
    credentials.put(
        "disabled user", new Credential("Authorization", "Bearer " + jwt.issueAccessToken(disabledUser)));
    credentials.put("service account", new Credential("X-API-Key", "phase-a-service-key"));

    when(compiler.compile(anyString(), any()))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              Set<String> domains =
                  sql.contains("cross") ? Set.of("retail", "ai_rnd") : Set.of("retail");
              return new CompiledQuery("SELECT 1", List.of(), List.of(), "trace", domains);
            });
    when(executor.execute(any(), anyList()))
        .thenReturn(new QueryResult(List.of(), List.of(), "query-id"));
    doAnswer(
            invocation -> {
              policy.requireWriteDomain(invocation.getArgument(0), "retail");
              return null;
            })
        .when(objectCrud)
        .delete(any(SemanticPrincipal.class), anyString());
  }

  @Test
  void permissionMatrixIsConsistentThroughPolicyAndRest() throws Exception {
    Map<String, Expected> matrix =
        Map.of(
            "admin", new Expected(true, true, true, true),
            "domain-reader", new Expected(true, false, false, false),
            "domain-writer", new Expected(true, true, false, true),
            "no-grant user", new Expected(false, false, false, false),
            "disabled user", new Expected(false, false, false, false),
            "service account", new Expected(true, true, true, true));
    SemanticModel model = catalog.model();
    SemanticModel.SemanticObject retail = model.resolveObject("orders", "retail").value();

    for (Map.Entry<String, Expected> row : matrix.entrySet()) {
      String actor = row.getKey();
      Expected expected = row.getValue();
      SemanticPrincipal principal = principal(actor);
      assertThat(policy.canReadObject(principal, model, retail)).as(actor + " read").isEqualTo(expected.read());
      assertThat(policy.canQueryDomain(principal, "retail")).as(actor + " query").isEqualTo(expected.query());
      assertThat(
              policy.canQueryDomain(principal, "retail")
                  && policy.canQueryDomain(principal, "ai_rnd"))
          .as(actor + " cross-domain query")
          .isEqualTo(expected.crossQuery());
      assertThat(policy.canWriteDomain(principal, "retail"))
          .as(actor + " model CRUD")
          .isEqualTo(expected.write());

      expect(read("/api/v1/objects/retail.orders", actor), actor, expected.read() ? 200 : actor.equals("disabled user") ? 401 : 404);
      expect(
          query("retail query", actor),
          actor,
          expected.query() ? 200 : actor.equals("disabled user") ? 401 : expected.read() ? 403 : 400);
      expect(
          query("cross query", actor),
          actor,
          expected.crossQuery() ? 200 : actor.equals("disabled user") ? 401 : 400);
      expect(
          write(actor),
          actor,
          expected.write() ? 204 : actor.equals("disabled user") ? 401 : expected.read() ? 403 : 404);
    }
  }

  @Test
  void inaccessibleDomainsDoNotLeakThroughListingsSearchOrDirectFetch() throws Exception {
    Credential noGrant = credentials.get("no-grant user");
    mvc.perform(noGrant.apply(get("/api/v1/objects")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(noGrant.apply(get("/api/v1/metrics")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(noGrant.apply(get("/api/v1/search").param("q", "")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(noGrant.apply(get("/api/v1/objects/retail.orders")))
        .andExpect(status().isNotFound());
  }

  @Test
  void validBearerIsAcceptedAndDisabledUserTokenIsRejected() throws Exception {
    mvc.perform(credentials.get("domain-reader").apply(get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("reader"));
    mvc.perform(credentials.get("domain-writer").apply(get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.domainPermissions.retail")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "READ", "QUERY", "WRITE")))
        .andExpect(
            jsonPath("$.capabilities[0]").value("VIEW_COMPILED_SQL"));
    mvc.perform(credentials.get("admin").apply(get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin").value(true))
        .andExpect(jsonPath("$.domainPermissions").isEmpty())
        .andExpect(jsonPath("$.capabilities").isEmpty());
    mvc.perform(credentials.get("disabled user").apply(get("/api/v1/auth/me")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void loginRefreshAndMeShareTheResolvedUserViewShape() throws Exception {
    JsonNode login = login("writer", PASSWORD);
    assertResolvedWriter(login.get("user"));

    JsonNode refreshed = refresh(login.get("refreshToken").asText(), 200);
    assertResolvedWriter(refreshed.get("user"));

    String me =
        mvc.perform(
                get("/api/v1/auth/me")
                    .header(
                        "Authorization",
                        "Bearer " + refreshed.get("accessToken").asText()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertResolvedWriter(mapper.readTree(me));
  }

  @Test
  void browserRefreshCookieIsStrictHttpOnlyRotatedAndCleared() throws Exception {
    var login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .secure(true)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("username", "reader", "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    String loginHeader = login.getResponse().getHeader("Set-Cookie");
    assertThat(loginHeader)
        .contains("witness_refresh=")
        .contains("Path=/api/v1/auth")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("Secure");
    Cookie first = refreshCookie(loginHeader);

    var refreshed =
        mvc.perform(post("/api/v1/auth/refresh").cookie(first))
            .andExpect(status().isOk())
            .andReturn();
    Cookie replacement = refreshCookie(refreshed.getResponse().getHeader("Set-Cookie"));
    assertThat(replacement.getValue()).isNotEqualTo(first.getValue());
    assertThat(refreshed.getResponse().getHeader("Set-Cookie"))
        .contains("HttpOnly")
        .contains("SameSite=Strict");

    var logout =
        mvc.perform(post("/api/v1/auth/logout").cookie(replacement))
            .andExpect(status().isNoContent())
            .andReturn();
    assertThat(logout.getResponse().getHeader("Set-Cookie"))
        .contains("witness_refresh=")
        .contains("Max-Age=0")
        .contains("HttpOnly")
        .contains("SameSite=Strict");
    refresh(replacement.getValue(), 401);
  }

  @Test
  void serverPermissionContractMatchesTheClientMatrix() {
    SemanticPrincipal mixed =
        SemanticPrincipal.user(
            99,
            "matrix",
            "Matrix",
            "local",
            false,
            Set.of("mixed"),
            Map.of(
                "*", Set.of(SemanticPermission.READ),
                "retail",
                    Set.of(
                        SemanticPermission.READ,
                        SemanticPermission.QUERY,
                        SemanticPermission.WRITE)),
            Set.of());
    Map<PermissionCheck, Boolean> matrix =
        Map.of(
            new PermissionCheck("retail", SemanticPermission.READ), true,
            new PermissionCheck("retail", SemanticPermission.QUERY), true,
            new PermissionCheck("retail", SemanticPermission.WRITE), true,
            new PermissionCheck("ai_rnd", SemanticPermission.READ), true,
            new PermissionCheck("ai_rnd", SemanticPermission.QUERY), false,
            new PermissionCheck("unknown", SemanticPermission.READ), true,
            new PermissionCheck("unknown", SemanticPermission.WRITE), false);
    matrix.forEach(
        (check, expected) ->
            assertThat(mixed.hasPermission(check.domain(), check.permission()))
                .as(check.toString())
                .isEqualTo(expected));
  }

  @Test
  void refreshRotatesRejectsReuseAndLogoutRevokesReplacement() throws Exception {
    JsonNode login = login("nogrant", PASSWORD);
    String first = login.get("refreshToken").asText();
    JsonNode rotated = refresh(first, 200);
    String replacement = rotated.get("refreshToken").asText();
    refresh(first, 401);
    mvc.perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", replacement))))
        .andExpect(status().isNoContent());
    refresh(replacement, 401);
  }

  @Test
  void unknownUserAndWrongPasswordAreIndistinguishable() throws Exception {
    JsonNode unknown = failedLogin("does-not-exist", PASSWORD);
    JsonNode wrong = failedLogin("reader", "wrong-password");
    assertThat(unknown.get("status").asInt()).isEqualTo(401);
    assertThat(unknown.get("code").asText()).isEqualTo(wrong.get("code").asText());
    assertThat(unknown.get("message").asText()).isEqualTo(wrong.get("message").asText());
  }

  @Test
  void userCanChangeOwnPasswordAndClearMustChangeFlag() throws Exception {
    try {
      mvc.perform(
              credentials
                  .get("admin")
                  .apply(
                      post("/api/v1/auth/password")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              mapper.writeValueAsString(
                                  Map.of(
                                      "currentPassword", "admin",
                                      "newPassword", "new-admin-password")))))
          .andExpect(status().isNoContent());
      assertThat(
              jdbc.queryForObject(
                  "SELECT must_change_password FROM users WHERE username='admin'", Boolean.class))
          .isFalse();
      failedLogin("admin", "admin");
      assertThat(login("admin", "new-admin-password").get("user").get("mustChangePassword").asBoolean())
          .isFalse();
    } finally {
      jdbc.update(
          "UPDATE users SET password_hash=?,must_change_password=TRUE WHERE username='admin'",
          passwords.encode("admin"));
    }
  }

  private long role(String name, boolean admin, Set<SemanticPermission> grants) {
    jdbc.update(
        "INSERT INTO roles(name,description,is_admin) VALUES (?,?,?)", name, name, admin);
    long id = jdbc.queryForObject("SELECT id FROM roles WHERE name=?", Long.class, name);
    for (SemanticPermission grant : grants)
      jdbc.update(
          "INSERT INTO role_domain_grants(role_id,domain,permission) VALUES (?,?,?)",
          id,
          "retail",
          grant.name());
    return id;
  }

  private long user(String username, boolean enabled, long role) {
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
    jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", id, role);
    return id;
  }

  private SemanticPrincipal principal(String actor) {
    return switch (actor) {
      case "admin" -> identities.resolveUser(userId("admin")).orElseThrow();
      case "domain-reader" -> identities.resolveUser(userId("reader")).orElseThrow();
      case "domain-writer" -> identities.resolveUser(userId("writer")).orElseThrow();
      case "no-grant user" -> identities.resolveUser(userId("nogrant")).orElseThrow();
      case "disabled user" -> identities.resolveUser(userId("disabled")).orElse(SemanticPrincipal.anonymous());
      default -> authentication.authenticateApiKey("phase-a-service-key").orElseThrow();
    };
  }

  private long userId(String username) {
    return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
  }

  private JsonNode login(String username, String password) throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body);
  }

  private JsonNode failedLogin(String username, String password) throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body);
  }

  private JsonNode refresh(String token, int expected) throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("refreshToken", token))))
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
  }

  private Cookie refreshCookie(String header) {
    String value = header.substring("witness_refresh=".length(), header.indexOf(';'));
    return new Cookie("witness_refresh", value);
  }

  private void assertResolvedWriter(JsonNode user) {
    assertThat(user.get("username").asText()).isEqualTo("writer");
    assertThat(
            mapper.convertValue(
                user.get("domainPermissions").get("retail"), String[].class))
        .containsExactlyInAnyOrder("READ", "QUERY", "WRITE");
    assertThat(mapper.convertValue(user.get("capabilities"), String[].class))
        .containsExactly("VIEW_COMPILED_SQL");
  }

  private Credential bearer(JsonNode login) {
    return new Credential("Authorization", "Bearer " + login.get("accessToken").asText());
  }

  private MockHttpServletRequestBuilder read(String path, String actor) {
    return credentials.get(actor).apply(get(path));
  }

  private MockHttpServletRequestBuilder query(String sql, String actor) throws Exception {
    return credentials
        .get(actor)
        .apply(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("sql", sql, "parameters", List.of()))));
  }

  private MockHttpServletRequestBuilder write(String actor) {
    return credentials.get(actor).apply(delete("/api/v1/objects/retail.orders"));
  }

  private void expect(MockHttpServletRequestBuilder request, String actor, int status)
      throws Exception {
    mvc.perform(request).andExpect(result -> assertThat(result.getResponse().getStatus()).as(actor).isEqualTo(status));
  }

  private record Expected(boolean read, boolean query, boolean crossQuery, boolean write) {}

  private record PermissionCheck(String domain, SemanticPermission permission) {}

  private record Credential(String header, String value) {
    MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
      return request.header(header, value);
    }
  }
}
