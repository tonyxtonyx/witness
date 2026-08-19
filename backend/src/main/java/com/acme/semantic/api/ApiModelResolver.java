package com.acme.semantic.api;

import com.acme.semantic.model.SemanticModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ApiModelResolver {
  private ApiModelResolver() {}

  static SemanticModel.SemanticObject object(SemanticModel model, String reference) {
    SemanticModel.Resolution<SemanticModel.SemanticObject> resolution =
        model.resolveObject(reference);
    if (resolution.ambiguous())
      throw ambiguous("object", reference, resolution.candidates());
    if (!resolution.found())
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Unknown object: " + reference);
    return resolution.value();
  }

  static SemanticModel.Metric metric(SemanticModel model, String reference) {
    SemanticModel.Resolution<SemanticModel.Metric> resolution = model.resolveMetric(reference);
    if (resolution.ambiguous())
      throw ambiguous("metric", reference, resolution.candidates());
    if (!resolution.found())
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Unknown metric: " + reference);
    return resolution.value();
  }

  static ResponseStatusException ambiguous(
      String kind, String reference, java.util.List<String> candidates) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Ambiguous "
            + kind
            + " reference: "
            + reference
            + "; candidates: "
            + String.join(", ", candidates));
  }
}
