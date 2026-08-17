package com.acme.semantic.core;

import java.util.List;
import java.util.Map;

public final class SemanticException extends RuntimeException {
  private final SemanticErrorCode code;
  private final boolean retryable;
  private final Map<String, Object> details;
  private final List<String> suggestions;

  public SemanticException(
      SemanticErrorCode code,
      String message,
      boolean retryable,
      Map<String, Object> details,
      List<String> suggestions) {
    super(message);
    this.code = code;
    this.retryable = retryable;
    this.details = details == null ? Map.of() : Map.copyOf(details);
    this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
  }

  public SemanticException(SemanticErrorCode code, String message) {
    this(code, message, false, Map.of(), List.of());
  }

  public SemanticErrorCode code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public Map<String, Object> details() {
    return details;
  }

  public List<String> suggestions() {
    return suggestions;
  }
}
