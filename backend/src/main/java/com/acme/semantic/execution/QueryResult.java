package com.acme.semantic.execution;

import java.util.List;

public record QueryResult(List<Column> columns, List<List<Object>> rows, String queryId) {
  public record Column(String name, int jdbcType, String typeName, boolean nullable) {}
}
