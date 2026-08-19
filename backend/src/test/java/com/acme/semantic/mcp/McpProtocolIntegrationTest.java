package com.acme.semantic.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:mcp-protocol;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.api-key=mcp-test-secret",
      "semantic.pgwire.enabled=false",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.trino.url=jdbc:trino://invalid:8080",
      "semantic.mcp.enabled=true"
    })
class McpProtocolIntegrationTest {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @LocalServerPort private int port;
  @Autowired private ObjectMapper mapper;

  @Test
  void invokesDiscoveryThroughActualMcpProtocol() throws Exception {
    HttpResponse<String> initialize =
        post(
            Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params",
                    Map.of(
                        "protocolVersion", "2025-11-25",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "witness-test", "version", "1.0"))),
            true,
            null);
    assertThat(initialize.statusCode()).isEqualTo(200);
    assertThat(json(initialize).get("result")).isInstanceOf(Map.class);

    HttpResponse<String> listed =
        post(
            Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()),
            true,
            null);
    assertThat(listed.statusCode()).isEqualTo(200);
    Map<String, Object> result = object(json(listed).get("result"));
    List<Map<String, Object>> tools = listOfObjects(result.get("tools"));
    assertThat(tools)
        .extracting(tool -> tool.get("name"))
        .containsExactly(
            "search_semantic_objects",
            "get_semantic_object",
            "get_metric_context",
            "get_dimension_values",
            "compile_semantic_query",
            "query_metrics",
            "get_lineage");

    HttpResponse<String> called =
        post(
            Map.of(
                "jsonrpc", "2.0",
                "id", 3,
                "method", "tools/call",
                "params",
                    Map.of(
                        "name", "search_semantic_objects",
                        "arguments", Map.of("query", "revenue"))),
            true,
            null);
    assertThat(called.statusCode()).isEqualTo(200);
    Map<String, Object> callResult = object(json(called).get("result"));
    Map<String, Object> structured = object(callResult.get("structuredContent"));
    assertThat(listOfObjects(structured.get("results")))
        .extracting(item -> item.get("id"))
        .contains("retail.total_revenue");
    assertThat(structured.get("traceId")).isNotNull();

    HttpResponse<String> compiled =
        post(
            Map.of(
                "jsonrpc", "2.0",
                "id", 4,
                "method", "tools/call",
                "params",
                    Map.of(
                        "name", "compile_semantic_query",
                        "arguments",
                            Map.of(
                                "query",
                                Map.of(
                                    "metrics", List.of("retail.total_revenue"),
                                    "dimensions",
                                        List.of(Map.of("id", "retail.customers.country")),
                                    "limit", 25)))),
            true,
            null);
    Map<String, Object> compileResult = object(json(compiled).get("result"));
    Map<String, Object> compiledContent = object(compileResult.get("structuredContent"));
    assertThat(compiledContent).containsEntry("valid", true).containsEntry("compiledSql", null);
    assertThat(object(compiledContent.get("plan")).get("joinPath"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("order_customer");
  }

  @Test
  void rejectsUnauthenticatedAndCrossOriginProtocolRequests() throws Exception {
    Map<String, Object> request =
        Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/list", "params", Map.of());

    assertThat(post(request, false, null).statusCode()).isEqualTo(401);
    assertThat(post(request, true, "https://attacker.example").statusCode()).isEqualTo(403);
  }

  private HttpResponse<String> post(
      Map<String, Object> body, boolean authenticated, String origin) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-11-25")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    if (authenticated) request.header("X-API-Key", "mcp-test-secret");
    if (origin != null) request.header("Origin", origin);
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> json(HttpResponse<String> response) throws Exception {
    return mapper.readValue(response.body(), MAP_TYPE);
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
