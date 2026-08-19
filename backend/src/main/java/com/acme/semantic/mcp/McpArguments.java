package com.acme.semantic.mcp;

import com.acme.semantic.core.SemanticErrorCode;
import com.acme.semantic.core.SemanticException;
import com.acme.semantic.core.SemanticLineageService;
import com.acme.semantic.core.SemanticMetadataService;
import com.acme.semantic.core.SemanticQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class McpArguments {
  private McpArguments() {}

  static Map<String, Object> strict(
      Map<String, Object> input, Collection<String> allowed, String path) {
    Map<String, Object> values = input == null ? Map.of() : input;
    Set<String> unknown = new LinkedHashSet<>(values.keySet());
    unknown.removeAll(allowed);
    if (!unknown.isEmpty()) {
      invalid("Unknown field(s): " + String.join(", ", unknown), path);
    }
    return values;
  }

  static String requiredString(Map<String, Object> input, String name, String path) {
    Object value = input.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      invalid("Required non-empty string: " + name, path + "." + name);
    }
    return ((String) value).trim();
  }

  static String optionalString(Map<String, Object> input, String name, String path) {
    Object value = input.get(name);
    if (value == null) return null;
    if (!(value instanceof String)) invalid("Expected a string", path + "." + name);
    return ((String) value).trim();
  }

  static boolean optionalBoolean(
      Map<String, Object> input, String name, boolean defaultValue, String path) {
    Object value = input.get(name);
    if (value == null) return defaultValue;
    if (!(value instanceof Boolean)) invalid("Expected a boolean", path + "." + name);
    return (Boolean) value;
  }

  static Boolean nullableBoolean(Map<String, Object> input, String name, String path) {
    Object value = input.get(name);
    if (value == null) return null;
    if (!(value instanceof Boolean)) invalid("Expected a boolean", path + "." + name);
    return (Boolean) value;
  }

  static int integer(
      Map<String, Object> input,
      String name,
      int defaultValue,
      int minimum,
      int maximum,
      String path) {
    Object value = input.get(name);
    if (value == null) return defaultValue;
    if (!(value instanceof Number number)
        || number.doubleValue() != Math.rint(number.doubleValue())) {
      invalid("Expected an integer", path + "." + name);
    }
    int result = ((Number) value).intValue();
    if (result < minimum || result > maximum) {
      invalid(
          name + " must be between " + minimum + " and " + maximum,
          path + "." + name);
    }
    return result;
  }

  static List<String> strings(
      Map<String, Object> input, String name, int maximum, String path) {
    Object value = input.get(name);
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) invalid("Expected an array", path + "." + name);
    List<?> list = (List<?>) value;
    if (list.size() > maximum) {
      invalid("Array contains too many values", path + "." + name);
    }
    List<String> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      Object item = list.get(i);
      if (!(item instanceof String text) || text.isBlank()) {
        invalid("Expected non-empty strings", path + "." + name + "[" + i + "]");
      }
      result.add(((String) item).trim());
    }
    return List.copyOf(result);
  }

  static Set<SemanticMetadataService.ObjectType> objectTypes(
      Map<String, Object> input, String name, String path) {
    List<String> values = strings(input, name, 3, path);
    Set<SemanticMetadataService.ObjectType> result = new LinkedHashSet<>();
    for (String value : values) {
      try {
        result.add(
            SemanticMetadataService.ObjectType.valueOf(value.toLowerCase(Locale.ROOT)));
      } catch (IllegalArgumentException exception) {
        invalid("Unknown semantic object type: " + value, path + "." + name);
      }
    }
    return Set.copyOf(result);
  }

  static Set<SemanticLineageService.NodeType> lineageTypes(
      Map<String, Object> input, String name, String path) {
    List<String> values = strings(input, name, 4, path);
    Set<SemanticLineageService.NodeType> result = new LinkedHashSet<>();
    for (String value : values) {
      try {
        result.add(SemanticLineageService.NodeType.valueOf(value.toLowerCase(Locale.ROOT)));
      } catch (IllegalArgumentException exception) {
        invalid("Unknown lineage object type: " + value, path + "." + name);
      }
    }
    return Set.copyOf(result);
  }

  static SemanticQuery query(Object value, String path) {
    Map<String, Object> input = object(value, path);
    strict(
        input,
        Set.of("metrics", "dimensions", "filters", "orderBy", "limit", "timezone", "joinPaths"),
        path);
    List<String> metrics = strings(input, "metrics", 20, path);
    List<SemanticQuery.DimensionSelection> dimensions = dimensions(input.get("dimensions"), path);
    SemanticQuery.FilterGroup filters = filters(input.get("filters"), path + ".filters");
    List<SemanticQuery.OrderBy> orderBy = orderBy(input.get("orderBy"), path);
    Integer limit = input.containsKey("limit") ? rawInteger(input.get("limit"), path + ".limit") : null;
    String timezone = optionalString(input, "timezone", path);
    List<SemanticQuery.JoinPath> joinPaths = joinPaths(input.get("joinPaths"), path);
    return new SemanticQuery(metrics, dimensions, filters, orderBy, limit, timezone, joinPaths);
  }

  private static List<SemanticQuery.JoinPath> joinPaths(Object value, String path) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) invalid("joinPaths must be an array", path + ".joinPaths");
    List<?> list = (List<?>) value;
    if (list.size() > 20) invalid("Too many join paths", path + ".joinPaths");
    List<SemanticQuery.JoinPath> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + ".joinPaths[" + i + "]";
      Map<String, Object> item = object(list.get(i), itemPath);
      strict(item, Set.of("to", "via"), itemPath);
      String to = requiredString(item, "to", itemPath);
      List<String> via = strings(item, "via", 20, itemPath);
      if (via.isEmpty()) invalid("Join path via must not be empty", itemPath + ".via");
      result.add(new SemanticQuery.JoinPath(to, via));
    }
    return List.copyOf(result);
  }

  static SemanticQuery.FilterGroup filters(Object value, String path) {
    if (value == null) return null;
    Map<String, Object> input = object(value, path);
    strict(input, Set.of("operator", "conditions"), path);
    String operator = requiredString(input, "operator", path).toUpperCase(Locale.ROOT);
    SemanticQuery.LogicalOperator logical;
    try {
      logical = SemanticQuery.LogicalOperator.valueOf(operator);
    } catch (IllegalArgumentException exception) {
      invalid("Filter operator must be and or or", path + ".operator");
      return null;
    }
    Object raw = input.get("conditions");
    if (!(raw instanceof List<?> list)) invalid("conditions must be an array", path + ".conditions");
    List<?> list = (List<?>) raw;
    if (list.size() > 50) invalid("Too many filter conditions", path + ".conditions");
    List<SemanticQuery.FilterCondition> conditions = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + ".conditions[" + i + "]";
      Map<String, Object> condition = object(list.get(i), itemPath);
      strict(condition, Set.of("member", "operator", "values"), itemPath);
      String member = requiredString(condition, "member", itemPath);
      String operatorValue =
          requiredString(condition, "operator", itemPath)
              .toUpperCase(Locale.ROOT)
              .replace('-', '_');
      SemanticQuery.FilterOperator filterOperator;
      try {
        filterOperator = SemanticQuery.FilterOperator.valueOf(operatorValue);
      } catch (IllegalArgumentException exception) {
        invalid("Unknown typed filter operator", itemPath + ".operator");
        return null;
      }
      List<Object> values = scalarValues(condition.get("values"), itemPath + ".values");
      conditions.add(new SemanticQuery.FilterCondition(member, filterOperator, values));
    }
    return new SemanticQuery.FilterGroup(logical, conditions);
  }

  private static List<SemanticQuery.DimensionSelection> dimensions(
      Object value, String path) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) invalid("dimensions must be an array", path + ".dimensions");
    List<?> list = (List<?>) value;
    if (list.size() > 50) invalid("Too many dimensions", path + ".dimensions");
    List<SemanticQuery.DimensionSelection> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + ".dimensions[" + i + "]";
      Map<String, Object> item = object(list.get(i), itemPath);
      strict(item, Set.of("id", "granularity"), itemPath);
      String id = requiredString(item, "id", itemPath);
      String rawGranularity = optionalString(item, "granularity", itemPath);
      SemanticQuery.TimeGranularity granularity = null;
      if (rawGranularity != null) {
        try {
          granularity =
              SemanticQuery.TimeGranularity.valueOf(rawGranularity.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
          invalid("Unknown time granularity", itemPath + ".granularity");
        }
      }
      result.add(new SemanticQuery.DimensionSelection(id, granularity));
    }
    return List.copyOf(result);
  }

  private static List<SemanticQuery.OrderBy> orderBy(Object value, String path) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) invalid("orderBy must be an array", path + ".orderBy");
    List<?> list = (List<?>) value;
    if (list.size() > 20) invalid("Too many orderBy entries", path + ".orderBy");
    List<SemanticQuery.OrderBy> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + ".orderBy[" + i + "]";
      Map<String, Object> item = object(list.get(i), itemPath);
      strict(item, Set.of("member", "direction"), itemPath);
      String member = requiredString(item, "member", itemPath);
      String rawDirection = optionalString(item, "direction", itemPath);
      SemanticQuery.SortDirection direction = SemanticQuery.SortDirection.ASC;
      if (rawDirection != null) {
        try {
          direction = SemanticQuery.SortDirection.valueOf(rawDirection.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
          invalid("Sort direction must be asc or desc", itemPath + ".direction");
        }
      }
      result.add(new SemanticQuery.OrderBy(member, direction));
    }
    return List.copyOf(result);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String path) {
    if (!(value instanceof Map<?, ?> map)) invalid("Expected an object", path);
    Map<?, ?> map = (Map<?, ?>) value;
    for (Object key : map.keySet()) {
      if (!(key instanceof String)) invalid("Object keys must be strings", path);
    }
    return (Map<String, Object>) map;
  }

  private static List<Object> scalarValues(Object value, String path) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) invalid("values must be an array", path);
    List<?> list = (List<?>) value;
    if (list.size() > 100) invalid("Too many filter values", path);
    List<Object> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      Object item = list.get(i);
      if (item instanceof Map<?, ?> || item instanceof List<?>) {
        invalid("Filter values must be JSON scalars", path + "[" + i + "]");
      }
      result.add(item);
    }
    return List.copyOf(result);
  }

  private static int rawInteger(Object value, String path) {
    if (!(value instanceof Number number)
        || number.doubleValue() != Math.rint(number.doubleValue())) {
      invalid("Expected an integer", path);
    }
    return ((Number) value).intValue();
  }

  private static void invalid(String message, String path) {
    throw new SemanticException(
        SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
        message,
        false,
        Map.of("path", path),
        List.of());
  }
}
