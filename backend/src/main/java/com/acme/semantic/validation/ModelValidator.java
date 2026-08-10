package com.acme.semantic.validation;

import com.acme.semantic.model.SemanticModel;

public interface ModelValidator {
  ValidationResult validate(SemanticModel model);
}
