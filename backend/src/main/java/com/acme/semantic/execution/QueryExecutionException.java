package com.acme.semantic.execution;

public class QueryExecutionException extends RuntimeException {
  private final String sqlState;

  public QueryExecutionException(String state, String message, Throwable cause) {
    super(message, cause);
    sqlState = state;
  }

  public String sqlState() {
    return sqlState;
  }
}
