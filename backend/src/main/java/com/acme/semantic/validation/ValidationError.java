package com.acme.semantic.validation;

public record ValidationError(
    String file, String path, String code, String message, Severity severity) {
  public enum Severity {
    ERROR,
    WARNING
  }
}
