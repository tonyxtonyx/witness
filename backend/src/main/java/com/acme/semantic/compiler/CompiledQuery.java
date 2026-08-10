package com.acme.semantic.compiler;

import java.util.List;

public record CompiledQuery(
    String trinoSql, List<Parameter> parameters, List<Column> columns, String correlationId) {
  public record Parameter(int index) {}

  public record Column(String name, String semanticType) {}
}
