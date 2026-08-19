package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.acme.semantic.execution.QueryExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:access-oracle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.api-key=oracle-service-key",
      "semantic.allow-insecure-api-key=false",
      "semantic.pgwire.enabled=false",
      "semantic.mcp.enabled=false",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.trino.url=jdbc:trino://invalid:8080",
      "witness.auth.jwt-secret=0123456789abcdef0123456789abcdef",
      "witness.auth.allow-default-admin=true",
      "witness.auth.access-token-minutes=60",
      "witness.auth.refresh-token-days=30"
    })
@AutoConfigureMockMvc
class AccessOracleIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @MockBean QueryExecutor executor;

  private String bearer;

  @BeforeEach
  void setUpAlice() throws Exception {
    jdbc.update("DELETE FROM users WHERE username='alice'");
    jdbc.update("DELETE FROM roles WHERE name='alice-retail'");
    jdbc.update(
        "INSERT INTO roles(name,description,is_admin) VALUES ('alice-retail','test',FALSE)");
    long role = jdbc.queryForObject("SELECT id FROM roles WHERE name='alice-retail'", Long.class);
    jdbc.update(
        "INSERT INTO role_domain_grants(role_id,domain,permission) VALUES (?,?,?)",
        role,
        "retail",
        "READ");
    jdbc.update(
        "INSERT INTO role_domain_grants(role_id,domain,permission) VALUES (?,?,?)",
        role,
        "retail",
        "QUERY");
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO users(username,password_hash,provider,display_name,enabled,must_change_password,created_at,updated_at) VALUES (?,?,?,?,TRUE,FALSE,?,?)",
        "alice",
        passwords.encode("alice-password"),
        "local",
        "Alice",
        now,
        now);
    long user = jdbc.queryForObject("SELECT id FROM users WHERE username='alice'", Long.class);
    jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", user, role);
    String body =
        mvc.perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("username", "alice", "password", "alice-password"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    bearer = "Bearer " + mapper.readTree(body).get("accessToken").asText();
  }

  @Test
  void queryCannotDistinguishHiddenObjectFromFabricatedObject() throws Exception {
    assertEquivalent(
        json(
            post("/api/v1/query"),
            Map.of("sql", "SELECT 1 FROM ai_rnd.experiments", "parameters", java.util.List.of())),
        json(
            post("/api/v1/query"),
            Map.of("sql", "SELECT 1 FROM ai_rnd.does_not_exist", "parameters", java.util.List.of())),
        "/code",
        "/message");
    mvc.perform(
            auth(
                json(
                    post("/api/v1/query"),
                    Map.of(
                        "sql",
                        "SELECT 1 FROM ai_rnd.experiments",
                        "parameters",
                        java.util.List.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("42P01"))
        .andExpect(jsonPath("$.message").value("Unknown semantic object"));
  }

  @Test
  void validationSourceAndMetricObjectFilterCannotEnumerateHiddenObjects() throws Exception {
    assertEquivalent(
        json(
            post("/api/v1/validate"),
            Map.of("expression", "quality_score", "objectId", "ai_rnd.experiments", "aggregation", "sum")),
        json(
            post("/api/v1/validate"),
            Map.of("expression", "quality_score", "objectId", "ai_rnd.does_not_exist", "aggregation", "sum")),
        "/errors/0/code",
        "/errors/0/message");
    assertEquivalent(
        get("/api/v1/objects/ai_rnd.experiments/source"),
        get("/api/v1/objects/ai_rnd.does_not_exist/source"),
        "/code",
        "/message");
    assertEquivalent(
        get("/api/v1/metrics").param("object", "ai_rnd.experiments"),
        get("/api/v1/metrics").param("object", "ai_rnd.does_not_exist"),
        "/code",
        "/message");
  }

  @Test
  void directFetchAndCrudIdentifiersCannotEnumerateHiddenResources() throws Exception {
    assertEquivalent(
        get("/api/v1/objects/ai_rnd.experiments"),
        get("/api/v1/objects/ai_rnd.does_not_exist"),
        "/code",
        "/message");
    assertEquivalent(
        get("/api/v1/metrics/ai_rnd.average_model_quality"),
        get("/api/v1/metrics/ai_rnd.does_not_exist"),
        "/code",
        "/message");
    assertEquivalent(
        json(put("/api/v1/objects/ai_rnd.experiments"), Map.of()),
        json(put("/api/v1/objects/ai_rnd.does_not_exist"), Map.of()),
        "/code",
        "/message");
    assertEquivalent(
        delete("/api/v1/objects/ai_rnd.experiments"),
        delete("/api/v1/objects/ai_rnd.does_not_exist"),
        "/code",
        "/message");
    assertEquivalent(
        json(put("/api/v1/metrics/ai_rnd.average_model_quality"), Map.of()),
        json(put("/api/v1/metrics/ai_rnd.does_not_exist"), Map.of()),
        "/code",
        "/message");
    assertEquivalent(
        delete("/api/v1/metrics/ai_rnd.average_model_quality"),
        delete("/api/v1/metrics/ai_rnd.does_not_exist"),
        "/code",
        "/message");
    assertEquivalent(
        json(
            post("/api/v1/metrics"),
            Map.of(
                "metadata", Map.of("name", "oracle_metric", "domain", "retail"),
                "spec", Map.of("baseObject", "ai_rnd.experiments"))),
        json(
            post("/api/v1/metrics"),
            Map.of(
                "metadata", Map.of("name", "oracle_metric", "domain", "retail"),
                "spec", Map.of("baseObject", "ai_rnd.does_not_exist"))),
        "/code",
        "/message");
  }

  @Test
  void readableButNonWritableResourceStillReturnsForbidden() throws Exception {
    mvc.perform(auth(delete("/api/v1/metrics/retail.total_revenue")))
        .andExpect(status().isForbidden());
    mvc.perform(auth(delete("/api/v1/objects/retail.orders")))
        .andExpect(status().isForbidden());
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, Object body) throws Exception {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body));
  }

  private void assertEquivalent(
      MockHttpServletRequestBuilder hidden,
      MockHttpServletRequestBuilder fabricated,
      String codePointer,
      String messagePointer)
      throws Exception {
    MvcResult hiddenResult = mvc.perform(auth(hidden)).andReturn();
    MvcResult fabricatedResult = mvc.perform(auth(fabricated)).andReturn();
    JsonNode hiddenBody = mapper.readTree(hiddenResult.getResponse().getContentAsString());
    JsonNode fabricatedBody = mapper.readTree(fabricatedResult.getResponse().getContentAsString());
    assertThat(hiddenResult.getResponse().getStatus())
        .isEqualTo(fabricatedResult.getResponse().getStatus());
    assertThat(hiddenBody.at(codePointer).asText())
        .isEqualTo(fabricatedBody.at(codePointer).asText());
    assertThat(hiddenBody.at(messagePointer).asText())
        .isEqualTo(fabricatedBody.at(messagePointer).asText());
  }

  private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", bearer);
  }
}
