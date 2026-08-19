package com.acme.semantic.compiler;

import java.util.List;
import java.util.Set;

public record CompiledQuery(
    String trinoSql,
    List<Parameter> parameters,
    List<Column> columns,
    String correlationId,
    Set<String> domains) {
  public CompiledQuery(
      String trinoSql, List<Parameter> parameters, List<Column> columns, String correlationId) {
    this(trinoSql, parameters, columns, correlationId, Set.of());
  }

  public CompiledQuery {
    domains = domains == null ? Set.of() : Set.copyOf(domains);
  }

  public record Parameter(int index) {}

  public record Column(String name, String semanticType) {}
}
