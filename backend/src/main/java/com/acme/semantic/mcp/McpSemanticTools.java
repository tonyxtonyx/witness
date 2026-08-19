package com.acme.semantic.mcp;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticErrorCode;
import com.acme.semantic.core.SemanticException;
import com.acme.semantic.core.SemanticLineageService;
import com.acme.semantic.core.SemanticMetadataService;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.core.SemanticQuery;
import com.acme.semantic.core.SemanticQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class McpSemanticTools {
  public static final String PRINCIPAL_CONTEXT_KEY = "semanticPrincipal";
  public static final String TRACE_CONTEXT_KEY = "traceId";

  private static final Logger log = LoggerFactory.getLogger(McpSemanticTools.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final SemanticCatalog catalog;
  private final SemanticMetadataService metadata;
  private final SemanticQueryService queries;
  private final SemanticLineageService lineage;
  private final McpAuditLogger audit;
  private final ObjectMapper mapper;
  private final SemanticProperties.Mcp config;
  private final McpToolSchemas schemas;

  public McpSemanticTools(
      SemanticCatalog catalog,
      SemanticMetadataService metadata,
      SemanticQueryService queries,
      SemanticLineageService lineage,
      McpAuditLogger audit,
      ObjectMapper mapper,
      SemanticProperties properties) {
    this.catalog = catalog;
    this.metadata = metadata;
    this.queries = queries;
    this.lineage = lineage;
    this.audit = audit;
    this.mapper = mapper;
    this.config = properties.mcp() == null ? SemanticProperties.Mcp.defaults() : properties.mcp();
    this.schemas = new McpToolSchemas(properties);
  }

  public List<McpStatelessServerFeatures.SyncToolSpecification> specifications() {
    List<McpStatelessServerFeatures.SyncToolSpecification> result = new ArrayList<>();
    for (McpToolSchemas.Definition definition : schemas.definitions()) {
      McpSchema.Tool tool =
          McpSchema.Tool.builder(definition.name(), definition.inputSchema())
              .description(definition.description())
              .outputSchema(definition.outputSchema())
              .annotations(
                  McpSchema.ToolAnnotations.builder()
                      .title(title(definition.name()))
                      .readOnlyHint(true)
                      .destructiveHint(false)
                      .idempotentHint(true)
                      .openWorldHint(
                          definition.name().equals("query_metrics")
                              || definition.name().equals("get_dimension_values"))
                      .build())
              .build();
      result.add(
          McpStatelessServerFeatures.SyncToolSpecification.builder()
              .tool(tool)
              .callHandler(handler(definition.name()))
              .build());
    }
    return List.copyOf(result);
  }

  private BiFunction<
          McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult>
      handler(String tool) {
    return (context, request) -> execute(tool, context, request.arguments());
  }

  private McpSchema.CallToolResult execute(
      String tool, McpTransportContext context, Map<String, Object> arguments) {
    long started = System.nanoTime();
    String traceId = traceId(context);
    SemanticPrincipal principal = principal(context);
    String status = "success";
    String queryId = null;
    try {
      Object value = dispatch(tool, principal, arguments, traceId);
      Map<String, Object> structured = withTrace(value, traceId);
      if (Boolean.FALSE.equals(structured.get("valid"))) status = "validation_error";
      queryId = structured.get("queryId") instanceof String id ? id : null;
      return success(structured);
    } catch (SemanticException exception) {
      status = exception.code().name().toLowerCase(Locale.ROOT);
      return error(
          new ToolError(
              exception.code().name(),
              exception.getMessage(),
              exception.retryable(),
              traceId,
              exception.details(),
              exception.suggestions()));
    } catch (Exception exception) {
      status = "internal_error";
      log.error("Unexpected MCP tool failure tool={} traceId={}", tool, traceId, exception);
      return error(
          new ToolError(
              SemanticErrorCode.EXECUTION_FAILURE.name(),
              "The semantic tool failed. Contact the Witness operator with the trace ID.",
              false,
              traceId,
              Map.of(),
              List.of()));
    } finally {
      String revision;
      try {
        revision = catalog.model().revision();
      } catch (Exception ignored) {
        revision = null;
      }
      audit.record(
          principal.id(),
          tool,
          revision,
          (System.nanoTime() - started) / 1_000_000,
          status,
          traceId,
          queryId);
    }
  }

  private Object dispatch(
      String tool,
      SemanticPrincipal principal,
      Map<String, Object> arguments,
      String traceId) {
    Map<String, Object> input = arguments == null ? Map.of() : arguments;
    return switch (tool) {
      case "search_semantic_objects" -> search(principal, input);
      case "get_semantic_object" -> getObject(principal, input);
      case "get_metric_context" -> metricContext(principal, input);
      case "get_dimension_values" -> dimensionValues(principal, input, traceId);
      case "compile_semantic_query" -> compile(principal, input, traceId);
      case "query_metrics" -> query(principal, input, traceId);
      case "get_lineage" -> getLineage(principal, input);
      default ->
          throw new SemanticException(
              SemanticErrorCode.INVALID_TOOL_ARGUMENTS, "Unknown MCP tool: " + tool);
    };
  }

  private Object search(SemanticPrincipal principal, Map<String, Object> raw) {
    Map<String, Object> input =
        McpArguments.strict(
            raw,
            Set.of("query", "objectTypes", "domain", "tags", "certified", "limit", "cursor"),
            "$");
    int limit =
        McpArguments.integer(
            input, "limit", Math.min(20, config.searchMaxResults()), 1, config.searchMaxResults(), "$");
    return metadata.search(
        principal,
        new SemanticMetadataService.SearchRequest(
            McpArguments.optionalString(input, "query", "$"),
            McpArguments.objectTypes(input, "objectTypes", "$"),
            McpArguments.optionalString(input, "domain", "$"),
            Set.copyOf(new LinkedHashSet<>(McpArguments.strings(input, "tags", 20, "$"))),
            McpArguments.nullableBoolean(input, "certified", "$"),
            limit,
            McpArguments.optionalString(input, "cursor", "$")));
  }

  private Object getObject(SemanticPrincipal principal, Map<String, Object> raw) {
    Map<String, Object> input = McpArguments.strict(raw, Set.of("id"), "$");
    return metadata.get(principal, McpArguments.requiredString(input, "id", "$"));
  }

  private Object metricContext(SemanticPrincipal principal, Map<String, Object> raw) {
    Map<String, Object> input = McpArguments.strict(raw, Set.of("metricId"), "$");
    return metadata.metricContext(
        principal, McpArguments.requiredString(input, "metricId", "$"));
  }

  private Object dimensionValues(
      SemanticPrincipal principal,
      Map<String, Object> raw,
      String traceId) {
    Map<String, Object> input =
        McpArguments.strict(
            raw,
            Set.of("dimensionId", "metricIds", "search", "filters", "limit", "cursor"),
            "$");
    int limit =
        McpArguments.integer(
            input,
            "limit",
            config.dimensionDefaultRows(),
            1,
            config.dimensionMaxRows(),
            "$");
    return queries.dimensionValues(
        principal,
        new SemanticQueryService.DimensionValuesRequest(
            McpArguments.requiredString(input, "dimensionId", "$"),
            McpArguments.strings(input, "metricIds", 20, "$"),
            McpArguments.optionalString(input, "search", "$"),
            McpArguments.filters(input.get("filters"), "$.filters"),
            limit,
            McpArguments.optionalString(input, "cursor", "$")),
        traceId);
  }

  private Object compile(
      SemanticPrincipal principal,
      Map<String, Object> raw,
      String traceId) {
    Map<String, Object> input = McpArguments.strict(raw, Set.of("query"), "$");
    if (!input.containsKey("query")) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS, "Required object: query");
    }
    return queries.compile(principal, McpArguments.query(input.get("query"), "$.query"), traceId);
  }

  private Object query(
      SemanticPrincipal principal,
      Map<String, Object> raw,
      String traceId) {
    Map<String, Object> input = McpArguments.strict(raw, Set.of("query"), "$");
    if (!input.containsKey("query")) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS, "Required object: query");
    }
    return queries.query(principal, McpArguments.query(input.get("query"), "$.query"), traceId);
  }

  private Object getLineage(SemanticPrincipal principal, Map<String, Object> raw) {
    Map<String, Object> input =
        McpArguments.strict(
            raw,
            Set.of("objectId", "direction", "maxDepth", "objectTypes", "includePhysical"),
            "$");
    String rawDirection = McpArguments.optionalString(input, "direction", "$" );
    SemanticLineageService.Direction direction =
        rawDirection == null
            ? SemanticLineageService.Direction.both
            : parseDirection(rawDirection);
    int maxDepth =
        McpArguments.integer(
            input, "maxDepth", 2, 0, config.lineageMaxDepth(), "$");
    return lineage.lineage(
        principal,
        new SemanticLineageService.LineageRequest(
            McpArguments.requiredString(input, "objectId", "$"),
            direction,
            maxDepth,
            McpArguments.lineageTypes(input, "objectTypes", "$"),
            McpArguments.optionalBoolean(input, "includePhysical", false, "$")));
  }

  private SemanticLineageService.Direction parseDirection(String value) {
    try {
      return SemanticLineageService.Direction.valueOf(value.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
          "Lineage direction must be upstream, downstream, or both");
    }
  }

  private Map<String, Object> withTrace(Object value, String traceId) {
    Map<String, Object> converted = mapper.convertValue(value, MAP_TYPE);
    Map<String, Object> result = new LinkedHashMap<>(converted);
    if (value instanceof SemanticQueryService.CompilationResponse) {
      result.putIfAbsent("compiledSql", null);
    }
    result.putIfAbsent("traceId", traceId);
    return Collections.unmodifiableMap(result);
  }

  private McpSchema.CallToolResult success(Map<String, Object> structured) {
    return McpSchema.CallToolResult.builder()
        .structuredContent(structured)
        .addTextContent(json(structured))
        .isError(false)
        .build();
  }

  private McpSchema.CallToolResult error(ToolError error) {
    Map<String, Object> structured = mapper.convertValue(error, MAP_TYPE);
    return McpSchema.CallToolResult.builder()
        .structuredContent(structured)
        .addTextContent(json(structured))
        .isError(true)
        .build();
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      return "{\"code\":\"EXECUTION_FAILURE\",\"message\":\"Result serialization failed\"}";
    }
  }

  private SemanticPrincipal principal(McpTransportContext context) {
    Object value = context == null ? null : context.get(PRINCIPAL_CONTEXT_KEY);
    if (value instanceof SemanticPrincipal principal) return principal;
    return SemanticPrincipal.anonymous();
  }

  private String traceId(McpTransportContext context) {
    Object value = context == null ? null : context.get(TRACE_CONTEXT_KEY);
    return value instanceof String id && !id.isBlank() ? id : UUID.randomUUID().toString();
  }

  private String title(String name) {
    String value = name.replace('_', ' ');
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  public record ToolError(
      String code,
      String message,
      boolean retryable,
      String traceId,
      Map<String, Object> details,
      List<String> suggestions) {}
}
