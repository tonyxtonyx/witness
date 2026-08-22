package com.acme.semantic.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record QueryResult(
    List<Column> columns,
    List<List<Object>> rows,
    String queryId,
    boolean cacheHit,
    String correlationId) {
  public QueryResult(List<Column> columns, List<List<Object>> rows, String queryId) {
    this(columns, rows, queryId, false, null);
  }

  public QueryResult {
    columns = List.copyOf(columns);
    rows =
        rows.stream()
            .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
            .toList();
  }

  public record Column(String name, int jdbcType, String typeName, boolean nullable) {}
}
