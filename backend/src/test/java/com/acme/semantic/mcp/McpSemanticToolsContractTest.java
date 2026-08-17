package com.acme.semantic.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticLineageService;
import com.acme.semantic.core.SemanticMetadataService;
import com.acme.semantic.core.SemanticQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpSemanticToolsContractTest {
  private List<McpStatelessServerFeatures.SyncToolSpecification> tools;
  private McpTransportContext context;

  @BeforeEach
  void setUp() {
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(TestModels.demo());
    McpSemanticTools adapter =
        new McpSemanticTools(
            catalog,
            mock(SemanticMetadataService.class),
            mock(SemanticQueryService.class),
            mock(SemanticLineageService.class),
            mock(McpAuditLogger.class),
            new ObjectMapper().findAndRegisterModules(),
            new SemanticProperties("semantic-model", "test", null, null, null));
    tools = adapter.specifications();
    context =
        McpTransportContext.create(
            Map.of(
                McpSemanticTools.PRINCIPAL_CONTEXT_KEY,
                "test-key",
                McpSemanticTools.TRACE_CONTEXT_KEY,
                "trace-contract"));
  }

  @Test
  void exposesExactlySevenGovernedToolsWithStrictSchemas() {
    assertThat(tools)
        .extracting(specification -> specification.tool().name())
        .containsExactly(
            "search_semantic_objects",
            "get_semantic_object",
            "get_metric_context",
            "get_dimension_values",
            "compile_semantic_query",
            "query_metrics",
            "get_lineage");
    assertThat(tools)
        .allSatisfy(
            specification -> {
              assertThat(specification.tool().description())
                  .contains("Use")
                  .asString()
                  .containsIgnoringCase("do not");
              assertThat(specification.tool().inputSchema())
                  .containsEntry("additionalProperties", false);
              assertThat(specification.tool().outputSchema()).isNotNull().isNotEmpty();
              assertThat(specification.tool().annotations().readOnlyHint()).isTrue();
            });
  }

  @Test
  void rejectsIdentityAndUnknownFieldsWithStructuredError() {
    McpSchema.CallToolResult result =
        call("search_semantic_objects", Map.of("query", "revenue", "user", "admin"));

    assertThat(result.isError()).isTrue();
    assertThat(result.structuredContent())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("code", "INVALID_TOOL_ARGUMENTS")
        .containsEntry("traceId", "trace-contract");
  }

  @Test
  void rejectsRawSqlAndInvalidEnumsBeforeCoreInvocation() {
    McpSchema.CallToolResult rawSql =
        call(
            "query_metrics",
            Map.of(
                "query",
                Map.of(
                    "metrics", List.of("retail.total_revenue"),
                    "rawSql", "select * from postgres.public.orders")));
    McpSchema.CallToolResult direction =
        call("get_lineage", Map.of("objectId", "retail.orders", "direction", "sideways"));

    assertThat(rawSql.isError()).isTrue();
    assertThat(direction.isError()).isTrue();
    assertThat(rawSql.structuredContent())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("code", "INVALID_TOOL_ARGUMENTS");
  }

  @Test
  void rejectsMissingRequiredArguments() {
    McpSchema.CallToolResult result = call("get_semantic_object", Map.of());

    assertThat(result.isError()).isTrue();
    assertThat(result.structuredContent())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("code", "INVALID_TOOL_ARGUMENTS");
  }

  private McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
    McpStatelessServerFeatures.SyncToolSpecification specification =
        tools.stream()
            .filter(candidate -> candidate.tool().name().equals(name))
            .findFirst()
            .orElseThrow();
    return specification
        .callHandler()
        .apply(context, new McpSchema.CallToolRequest(name, arguments));
  }
}
