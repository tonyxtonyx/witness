package com.acme.semantic.compiler;

public class SqlCompilationException extends RuntimeException {
  private final String sqlState;

  public SqlCompilationException(String sqlState, String message) {
    super(message);
    this.sqlState = sqlState;
  }

  public String sqlState() {
    return sqlState;
  }
}
