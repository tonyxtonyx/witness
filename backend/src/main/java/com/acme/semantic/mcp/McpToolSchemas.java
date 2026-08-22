package com.acme.semantic.mcp;

import com.acme.semantic.config.SemanticProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpToolSchemas {
  private final SemanticProperties.Mcp config;

  McpToolSchemas(SemanticProperties properties) {
    this.config = properties.mcp() == null ? SemanticProperties.Mcp.defaults() : properties.mcp();
  }

  List<Definition> definitions() {
    return List.of(
        new Definition(
            "search_semantic_objects",
            "Use to discover accessible metrics, dimensions, and semantic objects by exact ID, name, title, description, or tags. Use this before requesting an unknown ID; do not use it to retrieve full definitions or data rows.",
            object(
                properties(
                    "query", string(),
                    "objectTypes", array(enumeration("metric", "dimension", "semantic_object"), 3),
                    "domain", string(),
                    "tags", array(string(), 20),
                    "certified", bool(),
                    "limit", integer(1, config.searchMaxResults()),
                    "cursor", string()),
                List.of()),
            searchOutput()),
        new Definition(
            "get_semantic_object",
            "Use after discovery to retrieve one authoritative compiled semantic definition by fully qualified ID. Do not use it for dimension values, analytical results, or raw physical SQL.",
            object(properties("id", string()), List.of("id")),
            objectOutput()),
        new Definition(
            "get_metric_context",
            "Use before composing a query to learn a metric's grain, governed filters, time semantics, and fan-out-safe compatible dimensions. Do not assume every catalog dimension is compatible.",
            object(properties("metricId", string()), List.of("metricId")),
            metricContextOutput()),
        new Definition(
            "get_dimension_values",
            "Use to discover a small governed set of filter values for one accessible dimension, optionally scoped by metrics and semantic filters. Do not use it to export a column or bypass high-cardinality protections.",
            dimensionValuesInput(),
            dimensionValuesOutput()),
        new Definition(
            "compile_semantic_query",
            "Use to validate and deterministically plan a canonical semantic query without reading result data. Use it to diagnose compatibility, filters, granularities, and joins. Do not pass raw SQL.",
            object(properties("query", querySchema()), List.of("query")),
            compileOutput()),
        new Definition(
            "query_metrics",
            "Use only after selecting governed semantic IDs to execute the canonical semantic query through the shared validator, compiler, policies, and Trino executor. Do not pass raw SQL or use it for bulk extraction.",
            object(properties("query", querySchema()), List.of("query")),
            queryOutput()),
        new Definition(
            "get_lineage",
            "Use to inspect bounded upstream or downstream semantic lineage for one accessible ID. Physical nodes are returned only when explicitly requested and server-authorized; do not use this to infer hidden sources.",
            lineageInput(),
            lineageOutput()));
  }

  private Map<String, Object> querySchema() {
    return querySchema(true);
  }

  private Map<String, Object> querySchema(boolean requireMetric) {
    Map<String, Object> dimension =
        object(
            properties(
                "id", string(),
                "granularity", enumeration("day", "week", "month", "quarter", "year")),
            List.of("id"));
    Map<String, Object> order =
        object(
            properties("member", string(), "direction", enumeration("asc", "desc")),
            List.of("member"));
    Map<String, Object> joinPath =
        object(
            properties("to", string(), "via", array(string(), 20, 1)),
            List.of("to", "via"));
    return object(
        properties(
            "metrics", array(string(), 20, requireMetric ? 1 : null),
            "dimensions", array(dimension, 50),
            "filters", filterSchema(),
            "orderBy", array(order, 20),
            "limit", integer(1, config.queryMaxRows()),
            "timezone", string(),
            "joinPaths", array(joinPath, 20)),
        requireMetric ? List.of("metrics") : List.of());
  }

  private Map<String, Object> filterSchema() {
    Map<String, Object> scalar = new LinkedHashMap<>();
    scalar.put("type", List.of("string", "number", "boolean", "null"));
    Map<String, Object> condition =
        object(
            properties(
                "member", string(),
                "operator", enumeration(
                    "eq", "neq", "in", "not_in", "gt", "gte", "lt", "lte", "between", "is_null", "is_not_null"),
                "values", array(scalar, 100)),
            List.of("member", "operator"));
    return object(
        properties(
            "operator", enumeration("and", "or"),
            "conditions", array(condition, 50)),
        List.of("operator", "conditions"));
  }

  private Map<String, Object> dimensionValuesInput() {
    return object(
        properties(
            "dimensionId", string(),
            "metricIds", array(string(), 20),
            "search", string(),
            "filters", filterSchema(),
            "limit", integer(1, config.dimensionMaxRows()),
            "cursor", string()),
        List.of("dimensionId"));
  }

  private Map<String, Object> lineageInput() {
    return object(
        properties(
            "objectId", string(),
            "direction", enumeration("upstream", "downstream", "both"),
            "maxDepth", integer(0, config.lineageMaxDepth()),
            "objectTypes", array(
                enumeration("metric", "dimension", "semantic_object", "physical_object"), 4),
            "includePhysical", bool()),
        List.of("objectId"));
  }

  private Map<String, Object> searchOutput() {
    Map<String, Object> result =
        object(
            properties(
                "id", string(),
                "type", enumeration("metric", "dimension", "semantic_object"),
                "name", string(),
                "title", nullable(string()),
                "description", nullable(string()),
                "domain", string(),
                "tags", array(string()),
                "certified", bool(),
                "score", number(),
                "matchReasons", array(string())),
            List.of("id", "type", "name", "domain", "tags", "certified", "score", "matchReasons"));
    return object(
        properties(
            "results", array(result),
            "nextCursor", nullable(string()),
            "semanticRevision", string(),
            "traceId", string()),
        List.of("results", "semanticRevision", "traceId"));
  }

  private Map<String, Object> objectOutput() {
    return object(
        properties(
            "id", string(),
            "type", enumeration("metric", "dimension", "semantic_object"),
            "name", string(),
            "title", nullable(string()),
            "description", nullable(string()),
            "domain", string(),
            "owner", nullable(string()),
            "tags", array(string()),
            "aliases", array(string()),
            "certified", bool(),
            "deprecated", bool(),
            "definition", openObject(),
            "semanticRevision", string(),
            "freshness", openObject(),
            "traceId", string()),
        List.of("id", "type", "name", "domain", "tags", "aliases", "certified", "deprecated", "definition", "semanticRevision", "traceId"));
  }

  private Map<String, Object> metricContextOutput() {
    Map<String, Object> metric =
        object(
            properties(
                "id", string(),
                "title", nullable(string()),
                "description", nullable(string()),
                "metricType", string(),
                "unit", nullable(string()),
                "defaultCurrency", nullable(string()),
                "certified", bool(),
                "additivity", string()),
            List.of("id", "metricType", "certified", "additivity"));
    Map<String, Object> dimension =
        object(
            properties(
                "id", string(),
                "title", nullable(string()),
                "type", string(),
                "joinPath", array(string())),
            List.of("id", "type", "joinPath"));
    return object(
        properties(
            "metric", metric,
            "grain", array(string()),
            "defaultTimeDimension", nullable(string()),
            "supportedTimeGranularities", array(string()),
            "compatibleDimensions", array(dimension),
            "entities", array(string()),
            "requiredFilters", array(openObject()),
            "owners", array(string()),
            "freshness", openObject(),
            "knownLimitations", array(string()),
            "semanticRevision", string(),
            "traceId", string()),
        List.of("metric", "grain", "compatibleDimensions", "entities", "requiredFilters", "owners", "freshness", "knownLimitations", "semanticRevision", "traceId"));
  }

  private Map<String, Object> dimensionValuesOutput() {
    Map<String, Object> dimension =
        object(properties("id", string(), "type", string()), List.of("id", "type"));
    Map<String, Object> value =
        object(
            properties("value", new LinkedHashMap<>(), "label", nullable(string())),
            List.of("value"));
    return object(
        properties(
            "dimension", dimension,
            "values", array(value),
            "nextCursor", nullable(string()),
            "truncated", bool(),
            "semanticRevision", string(),
            "execution", executionSchema(),
            "traceId", string()),
        List.of("dimension", "values", "truncated", "semanticRevision", "execution", "traceId"));
  }

  private Map<String, Object> compileOutput() {
    Map<String, Object> issue =
        object(
            properties(
                "code", string(),
                "severity", string(),
                "message", string(),
                "path", string(),
                "member", nullable(string()),
                "details", openObject(),
                "suggestions", array(string())),
            List.of("code", "severity", "message", "path", "details", "suggestions"));
    Map<String, Object> plan =
        object(
            properties(
                "metrics", array(string()),
                "dimensions", array(string()),
                "models", array(string()),
                "joinPath", array(string()),
                "estimatedComplexity", string()),
            List.of("metrics", "dimensions", "models", "joinPath", "estimatedComplexity"));
    return object(
        properties(
            "valid", bool(),
            "normalizedQuery", querySchema(false),
            "semanticRevision", string(),
            "plan", plan,
            "appliedPolicySummary", array(string()),
            "warnings", array(string()),
            "errors", array(issue),
            "compiledSql", nullable(string()),
            "traceId", string()),
        List.of("valid", "normalizedQuery", "semanticRevision", "plan", "appliedPolicySummary", "warnings", "errors", "traceId"));
  }

  private Map<String, Object> queryOutput() {
    Map<String, Object> column =
        object(
            properties(
                "name", string(),
                "type", string(),
                "role", enumeration("metric", "dimension"),
                "unit", nullable(string()),
                "nullable", bool()),
            List.of("name", "type", "role", "nullable"));
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", "array");
    return object(
        properties(
            "queryId", string(),
            "semanticRevision", string(),
            "columns", array(column),
            "rows", array(row),
            "rowCount", integer(0, config.queryMaxRows()),
            "truncated", bool(),
            "freshness", openObject(),
            "warnings", array(string()),
            "appliedPolicySummary", array(string()),
            "execution", executionSchema(),
            "traceId", string()),
        List.of("queryId", "semanticRevision", "columns", "rows", "rowCount", "truncated", "freshness", "warnings", "appliedPolicySummary", "execution", "traceId"));
  }

  private Map<String, Object> lineageOutput() {
    Map<String, Object> node =
        object(
            properties(
                "id", string(),
                "type", enumeration("metric", "dimension", "semantic_object", "physical_object"),
                "title", nullable(string()),
                "layer", enumeration("semantic", "physical")),
            List.of("id", "type", "layer"));
    Map<String, Object> edge =
        object(
            properties(
                "from", string(),
                "to", string(),
                "type", string(),
                "layer", enumeration("semantic", "physical")),
            List.of("from", "to", "type", "layer"));
    return object(
        properties(
            "root", string(),
            "nodes", array(node),
            "edges", array(edge),
            "truncated", bool(),
            "semanticRevision", string(),
            "traceId", string()),
        List.of("root", "nodes", "edges", "truncated", "semanticRevision", "traceId"));
  }

  private Map<String, Object> executionSchema() {
    return object(
        properties(
            "durationMs", integer(0, Integer.MAX_VALUE),
            "engineQueryId", nullable(string()),
            "cacheHit", bool(),
            "correlationId", string()),
        List.of("durationMs", "cacheHit", "correlationId"));
  }

  private Map<String, Object> string() {
    return Map.of("type", "string");
  }

  private Map<String, Object> bool() {
    return Map.of("type", "boolean");
  }

  private Map<String, Object> number() {
    return Map.of("type", "number");
  }

  private Map<String, Object> integer(int minimum, int maximum) {
    return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
  }

  private Map<String, Object> enumeration(String... values) {
    return Map.of("type", "string", "enum", List.of(values));
  }

  private Map<String, Object> nullable(Map<String, Object> schema) {
    return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
  }

  private Map<String, Object> array(Map<String, Object> items) {
    return array(items, null, null);
  }

  private Map<String, Object> array(Map<String, Object> items, int maximum) {
    return array(items, maximum, null);
  }

  private Map<String, Object> array(
      Map<String, Object> items, Integer maximum, Integer minimum) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("items", items);
    if (maximum != null) schema.put("maxItems", maximum);
    if (minimum != null) schema.put("minItems", minimum);
    return Map.copyOf(schema);
  }

  private Map<String, Object> object(
      Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) schema.put("required", required);
    schema.put("additionalProperties", false);
    return Map.copyOf(schema);
  }

  private Map<String, Object> openObject() {
    return Map.of("type", "object", "additionalProperties", true);
  }

  private Map<String, Object> properties(Object... values) {
    if (values.length % 2 != 0) throw new IllegalArgumentException("Property pairs required");
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      result.put((String) values[i], values[i + 1]);
    }
    return Map.copyOf(result);
  }

  record Definition(
      String name,
      String description,
      Map<String, Object> inputSchema,
      Map<String, Object> outputSchema) {}
}
