package com.acme.semantic.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

public record SemanticQuery(
    List<String> metrics,
    List<DimensionSelection> dimensions,
    FilterGroup filters,
    List<OrderBy> orderBy,
    Integer limit,
    String timezone) {
  public SemanticQuery {
    metrics = metrics == null ? List.of() : List.copyOf(metrics);
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
  }

  public record DimensionSelection(String id, TimeGranularity granularity) {}

  public record FilterGroup(LogicalOperator operator, List<FilterCondition> conditions) {
    public FilterGroup {
      operator = operator == null ? LogicalOperator.AND : operator;
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
  }

  public record FilterCondition(String member, FilterOperator operator, List<Object> values) {
    public FilterCondition {
      values = values == null ? List.of() : List.copyOf(values);
    }
  }

  public record OrderBy(String member, SortDirection direction) {
    public OrderBy {
      direction = direction == null ? SortDirection.ASC : direction;
    }
  }

  public enum LogicalOperator {
    AND("and"),
    OR("or");

    private final String wire;

    LogicalOperator(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  public enum FilterOperator {
    EQ("eq"),
    NEQ("neq"),
    IN("in"),
    NOT_IN("not_in"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    BETWEEN("between"),
    IS_NULL("is_null"),
    IS_NOT_NULL("is_not_null");

    private final String wire;

    FilterOperator(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  public enum TimeGranularity {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    QUARTER("quarter"),
    YEAR("year");

    private final String wire;

    TimeGranularity(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  public enum SortDirection {
    ASC("asc"),
    DESC("desc");

    private final String wire;

    SortDirection(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }
}
