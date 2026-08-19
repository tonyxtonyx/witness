package com.acme.semantic.api;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.model.SemanticModel;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Resolves caller-supplied semantic IDs without exposing resources hidden by READ policy. */
@Component
public class SemanticResourceAccess {
  private final SemanticCatalog catalog;
  private final SemanticAccessPolicy policy;

  public SemanticResourceAccess(SemanticCatalog catalog, SemanticAccessPolicy policy) {
    this.catalog = catalog;
    this.policy = policy;
  }

  public SemanticModel.SemanticObject readableObject(
      SemanticPrincipal principal, String reference) {
    List<SemanticModel.SemanticObject> visible = readableObjects(principal, reference);
    if (visible.size() == 1) return visible.getFirst();
    if (visible.size() > 1)
      throw ApiModelResolver.ambiguous(
          "object", reference, visible.stream().map(catalog.model()::objectId).toList());
    throw notFound("object");
  }

  public SemanticModel.SemanticObject readableObject(
      SemanticPrincipal principal, String name, String domain) {
    if (name == null || name.contains(".") || domain == null || domain.isBlank())
      return readableObject(principal, name);
    return readableObject(principal, domain + "." + name);
  }

  public List<SemanticModel.SemanticObject> readableObjects(
      SemanticPrincipal principal, String reference) {
    SemanticModel model = catalog.model();
    SemanticModel.Resolution<SemanticModel.SemanticObject> resolution = model.resolveObject(reference);
    if (resolution.found())
      return policy.canReadObject(principal, model, resolution.value())
          ? List.of(resolution.value())
          : List.of();
    return resolution.candidates().stream()
        .map(id -> model.objectById(id).orElse(null))
        .filter(Objects::nonNull)
        .filter(object -> policy.canReadObject(principal, model, object))
        .toList();
  }

  public SemanticModel.Metric readableMetric(SemanticPrincipal principal, String reference) {
    List<SemanticModel.Metric> visible = readableMetrics(principal, reference);
    if (visible.size() == 1) return visible.getFirst();
    if (visible.size() > 1)
      throw ApiModelResolver.ambiguous(
          "metric", reference, visible.stream().map(catalog.model()::metricId).toList());
    throw notFound("metric");
  }

  public List<SemanticModel.Metric> readableMetrics(
      SemanticPrincipal principal, String reference) {
    SemanticModel model = catalog.model();
    SemanticModel.Resolution<SemanticModel.Metric> resolution = model.resolveMetric(reference);
    if (resolution.found())
      return canReadMetric(principal, resolution.value())
          ? List.of(resolution.value())
          : List.of();
    return resolution.candidates().stream()
        .map(id -> model.metricById(id).orElse(null))
        .filter(Objects::nonNull)
        .filter(metric -> canReadMetric(principal, metric))
        .toList();
  }

  public boolean canReadMetric(SemanticPrincipal principal, SemanticModel.Metric metric) {
    SemanticModel model = catalog.model();
    SemanticModel.SemanticObject base =
        model.resolveObject(metric.spec().baseObject(), model.domain(metric)).value();
    return policy.canReadMetric(principal, model, metric)
        && base != null
        && policy.canReadObject(principal, model, base);
  }

  public SemanticModel.SemanticObject writableObject(
      SemanticPrincipal principal, String reference) {
    SemanticModel.SemanticObject object = readableObject(principal, reference);
    policy.requireWriteDomain(principal, catalog.model().domain(object));
    return object;
  }

  public SemanticModel.Metric writableMetric(SemanticPrincipal principal, String reference) {
    SemanticModel.Metric metric = readableMetric(principal, reference);
    policy.requireWriteDomain(principal, catalog.model().domain(metric));
    return metric;
  }

  private ResponseStatusException notFound(String kind) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Semantic " + kind + " was not found");
  }
}
