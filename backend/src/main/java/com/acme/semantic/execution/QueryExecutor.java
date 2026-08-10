package com.acme.semantic.execution;

import com.acme.semantic.compiler.CompiledQuery;
import java.util.List;

public interface QueryExecutor {
  QueryResult execute(CompiledQuery query, List<Object> parameters);
}
