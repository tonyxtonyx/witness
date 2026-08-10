package com.acme.semantic.compiler;

import com.acme.semantic.model.SemanticModel;

public interface SemanticSqlCompiler {
  CompiledQuery compile(String sql, SemanticModel model);
}
