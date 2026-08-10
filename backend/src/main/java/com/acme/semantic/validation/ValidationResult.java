package com.acme.semantic.validation;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors) {
  public static ValidationResult of(List<ValidationError> errors) {
    return new ValidationResult(
        errors.stream().noneMatch(e -> e.severity() == ValidationError.Severity.ERROR),
        List.copyOf(errors));
  }
}
