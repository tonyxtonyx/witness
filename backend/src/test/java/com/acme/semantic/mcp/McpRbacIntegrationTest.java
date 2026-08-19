package com.acme.semantic.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.acme.semantic.auth.AdminIdentityService;
import com.acme.semantic.auth.IdentityRepository;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:phase-b-mcp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.api-key=phase-b-mcp-service-key",
      "semantic.pgwire.enabled=false",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.trino.url=jdbc:trino://invalid:8080",
      "semantic.mcp.enabled=true",
      "witness.auth.jwt-secret=0123456789abcdef0123456789abcdef",
      "witness.auth.allow-default-admin=true"
    })
class McpRbacIntegrationTest {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String PASSWORD = "password-123";

  @LocalServerPort private int port;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PasswordEncoder passwords;
  @Autowired private AdminIdentityService administration;
  @Autowired private IdentityRepository identities;
  @MockBean private QueryExecutor executor;

  private String bearer;
  private long restrictedRole;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update("UPDATE refresh_tokens SET replaced_by=NULL");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM service_accounts WHERE name='mcp-rotating-agent'");
    jdbc.update("DELETE FROM users WHERE username='mcp-alice'");
    jdbc.update("DELETE FROM roles WHERE name='mcp-retail-analyst'");
    jdbc.update(
        "INSERT INTO roles(name,description,is_admin) VALUES ('mcp-retail-analyst','MCP retail analyst',FALSE)");
    restrictedRole =
        jdbc.queryForObject(
            "SELECT id FROM roles WHERE name='mcp-retail-analyst'", Long.class);
    for (String permission : List.of("READ", "QUERY"))
      jdbc.update(
          "INSERT INTO role_domain_grants(role_id,domain,permission) VALUES (?,?,?)",
          restrictedRole,
          "retail",
          permission);
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO users(username,password_hash,provider,display_name,enabled,must_change_password,created_at,updated_at) VALUES (?,?,?,?,TRUE,FALSE,?,?)",
        "mcp-alice",
        passwords.encode(PASSWORD),
        "local",
        "MCP Alice",
        now,
        now);
    long user = jdbc.queryForObject("SELECT id FROM users WHERE username='mcp-alice'", Long.class);
    jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", user, restrictedRole);
    when(executor.execute(any(), anyList()))
        .thenReturn(new QueryResult(List.of(), List.of(), "mcp-query-id"));
    bearer = "Bearer " + login().get("accessToken");
  }

  @Test
  void bearerPrincipalEnforcesReadAndQueryAcrossAllSevenTools() throws Exception {
    Map<String, Object> search = call("search_semantic_objects", Map.of("query", "orders"));
    assertSuccess(search);
    assertThat(results(search)).contains("retail.orders");
    assertThat(results(search)).noneMatch(id -> id.startsWith("ai_rnd."));

    assertSuccess(call("get_semantic_object", Map.of("id", "retail.orders")));
    assertSuccess(call("get_metric_context", Map.of("metricId", "retail.total_revenue")));
    assertSuccess(
        call(
            "get_dimension_values",
            Map.of("dimensionId", "retail.orders.status", "limit", 10)));
    assertSuccess(
        call(
            "compile_semantic_query",
            Map.of("query", Map.of("metrics", List.of("retail.total_revenue")))));
    assertSuccess(
        call(
            "query_metrics",
            Map.of("query", Map.of("metrics", List.of("retail.total_revenue")))));
    assertSuccess(call("get_lineage", Map.of("objectId", "retail.orders")));
  }

  @Test
  void hiddenExistingAndFabricatedIdentifiersHaveEquivalentMcpErrors() throws Exception {
    Map<String, Object> hiddenSearch =
        call("search_semantic_objects", Map.of("query", "experiments"));
    Map<String, Object> fabricatedSearch =
        call("search_semantic_objects", Map.of("query", "does_not_exist"));
    assertThat(results(hiddenSearch)).isEqualTo(results(fabricatedSearch)).isEmpty();

    equivalent(
        "get_semantic_object",
        Map.of("id", "ai_rnd.experiments"),
        Map.of("id", "ai_rnd.does_not_exist"));
    equivalent(
        "get_metric_context",
        Map.of("metricId", "ai_rnd.average_model_quality"),
        Map.of("metricId", "ai_rnd.does_not_exist"));
    equivalent(
        "get_dimension_values",
        Map.of("dimensionId", "ai_rnd.experiments.status"),
        Map.of("dimensionId", "ai_rnd.does_not_exist.status"));
    equivalent(
        "compile_semantic_query",
        Map.of("query", Map.of("metrics", List.of("ai_rnd.average_model_quality"))),
        Map.of("query", Map.of("metrics", List.of("ai_rnd.does_not_exist"))));
    equivalent(
        "query_metrics",
        Map.of("query", Map.of("metrics", List.of("ai_rnd.average_model_quality"))),
        Map.of("query", Map.of("metrics", List.of("ai_rnd.does_not_exist"))));
    equivalent(
        "get_lineage",
        Map.of("objectId", "ai_rnd.experiments"),
        Map.of("objectId", "ai_rnd.does_not_exist"));
  }

  @Test
  void serviceAccountAndBearerWorkWhileMissingAndInvalidCredentialsAreRejected()
      throws Exception {
    Map<String, Object> request =
        rpc(
            90,
            "tools/call",
            Map.of(
                "name", "search_semantic_objects",
                "arguments", Map.of("query", "orders")));
    assertThat(post(request, "X-API-Key", "phase-b-mcp-service-key").statusCode()).isEqualTo(200);
    assertThat(post(request, "Authorization", bearer).statusCode()).isEqualTo(200);
    assertThat(post(request, null, null).statusCode()).isEqualTo(401);
    assertThat(post(request, "X-API-Key", "invalid-key").statusCode()).isEqualTo(401);
    assertThat(post(request, "Authorization", "Bearer invalid-token").statusCode()).isEqualTo(401);
  }

  @Test
  void rotatingAServiceAccountInvalidatesItsPreviousMcpCredential() throws Exception {
    long adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", Long.class);
    SemanticPrincipal admin = identities.resolveUser(adminId).orElseThrow();
    AdminIdentityService.ServiceAccountSecret created =
        administration.createServiceAccount(admin, "mcp-rotating-agent", restrictedRole);
    Map<String, Object> request =
        rpc(91, "tools/list", Map.of());
    assertThat(post(request, "X-API-Key", created.apiKey()).statusCode()).isEqualTo(200);

    AdminIdentityService.ServiceAccountSecret rotated =
        administration.rotateServiceAccount(admin, created.serviceAccount().id());
    assertThat(post(request, "X-API-Key", created.apiKey()).statusCode()).isEqualTo(401);
    assertThat(post(request, "X-API-Key", rotated.apiKey()).statusCode()).isEqualTo(200);
  }

  private void equivalent(
      String tool, Map<String, Object> hiddenArguments, Map<String, Object> fakeArguments)
      throws Exception {
    Map<String, Object> hidden = structured(call(tool, hiddenArguments));
    Map<String, Object> fabricated = structured(call(tool, fakeArguments));
    assertThat(hidden.get("code")).as(tool).isEqualTo("SEMANTIC_OBJECT_NOT_FOUND");
    assertThat(hidden.get("code")).as(tool).isEqualTo(fabricated.get("code"));
    assertThat(hidden.get("message")).as(tool).isEqualTo(fabricated.get("message"));
  }

  private void assertSuccess(Map<String, Object> response) {
    Map<String, Object> result = object(response.get("result"));
    assertThat(result.get("isError")).isEqualTo(false);
  }

  private List<String> results(Map<String, Object> response) {
    return listOfObjects(structured(response).get("results")).stream()
        .map(result -> result.get("id").toString())
        .toList();
  }

  private Map<String, Object> call(String tool, Map<String, Object> arguments) throws Exception {
    HttpResponse<String> response =
        post(
            rpc(
                tool.hashCode(),
                "tools/call",
                Map.of("name", tool, "arguments", arguments)),
            "Authorization",
            bearer);
    assertThat(response.statusCode()).as(tool).isEqualTo(200);
    return mapper.readValue(response.body(), MAP_TYPE);
  }

  private Map<String, Object> structured(Map<String, Object> response) {
    return object(object(response.get("result")).get("structuredContent"));
  }

  private Map<String, Object> login() throws Exception {
    HttpResponse<String> response =
        post(
            Map.of("username", "mcp-alice", "password", PASSWORD),
            null,
            null,
            "/api/v1/auth/login");
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readValue(response.body(), MAP_TYPE);
  }

  private HttpResponse<String> post(
      Map<String, Object> body, String header, String value) throws Exception {
    return post(body, header, value, "/api/mcp");
  }

  private HttpResponse<String> post(
      Map<String, Object> body, String header, String value, String path) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    if (path.equals("/api/mcp")) {
      request.header("Accept", "application/json, text/event-stream");
      request.header("MCP-Protocol-Version", "2025-11-25");
    }
    if (header != null) request.header(header, value);
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> rpc(int id, String method, Map<String, Object> params) {
    return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listOfObjects(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
