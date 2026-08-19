package com.acme.semantic.api;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.gitlab.ModelRepository;
import com.acme.semantic.gitlab.MutableModelRepository;
import com.acme.semantic.model.*;
import com.acme.semantic.validation.ModelValidator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetricCrudService {
  private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*";
  private final ModelRepository repository;
  private final SemanticCatalog catalog;
  private final ModelParser parser;
  private final ModelValidator validator;
  private final SemanticAccessPolicy policy;
  private final SemanticResourceAccess access;
  private final ObjectMapper yaml =
      new ObjectMapper(new YAMLFactory()).setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Autowired
  public MetricCrudService(
      ModelRepository repository,
      SemanticCatalog catalog,
      ModelParser parser,
      ModelValidator validator,
      SemanticAccessPolicy policy,
      SemanticResourceAccess access) {
    this.repository = repository;
    this.catalog = catalog;
    this.parser = parser;
    this.validator = validator;
    this.policy = policy;
    this.access = access;
  }

  MetricCrudService(
      ModelRepository repository,
      SemanticCatalog catalog,
      ModelParser parser,
      ModelValidator validator) {
    this(repository, catalog, parser, validator, null, null);
  }

  public synchronized SemanticModel.Metric create(
      SemanticPrincipal principal, MetricInput input) {
    if (input != null && input.spec() != null)
      access.readableObject(
          principal,
          input.spec().baseObject(),
          input.metadata() == null ? null : input.metadata().domain());
    SemanticModel.Metric metric = normalize(input);
    policy.requireWriteDomain(principal, catalog.model().domain(metric));
    return create(input);
  }

  public synchronized SemanticModel.Metric update(
      SemanticPrincipal principal, String name, MetricInput input) {
    access.writableMetric(principal, name);
    if (input != null && input.spec() != null)
      access.readableObject(
          principal,
          input.spec().baseObject(),
          input.metadata() == null ? null : input.metadata().domain());
    SemanticModel.Metric replacement = normalize(input);
    policy.requireWriteDomain(principal, catalog.model().domain(replacement));
    return update(name, input);
  }

  public synchronized void delete(SemanticPrincipal principal, String name) {
    access.writableMetric(principal, name);
    delete(name);
  }

  public synchronized SemanticModel.Metric create(MetricInput input) {
    SemanticModel.Metric metric = normalize(input);
    String id = catalog.model().metricId(metric);
    if (catalog.model().metrics().containsKey(id))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Metric already exists: " + id);
    String path = path(metric);
    validateAndApply(Map.of(path, serialize(metric)), Set.of());
    return catalog.model().metricById(id).orElseThrow();
  }

  public synchronized SemanticModel.Metric update(String name, MetricInput input) {
    SemanticModel.Metric existing = require(name);
    SemanticModel.Metric metric = normalize(input);
    if (!existing.metadata().name().equals(metric.metadata().name()))
      throw new IllegalArgumentException("Metric name in the path and body must match");
    String path = path(metric);
    Set<String> deletions = path.equals(existing.file()) ? Set.of() : Set.of(existing.file());
    validateAndApply(Map.of(path, serialize(metric)), deletions);
    return catalog.model().metricById(catalog.model().metricId(metric)).orElseThrow();
  }

  public synchronized void delete(String name) {
    SemanticModel.Metric existing = require(name);
    validateAndApply(Map.of(), Set.of(existing.file()));
  }

  private SemanticModel.Metric require(String name) {
    return ApiModelResolver.metric(catalog.model(), name);
  }

  private SemanticModel.Metric normalize(MetricInput input) {
    if (input == null || input.metadata() == null || input.spec() == null)
      throw new IllegalArgumentException("metadata and spec are required");
    String name = input.metadata().name();
    if (name == null || !name.matches(IDENTIFIER))
      throw new IllegalArgumentException("Metric name must be a safe SQL identifier");
    String domain = input.metadata().domain();
    var baseResolution = catalog.model().resolveObject(input.spec().baseObject(), domain);
    if (baseResolution.ambiguous())
      throw ApiModelResolver.ambiguous(
          "base object", input.spec().baseObject(), baseResolution.candidates());
    var base = baseResolution.value();
    if ((domain == null || domain.isBlank()) && base != null) domain = catalog.model().domain(base);
    var metadata =
        new SemanticModel.Metadata(
            name,
            domain,
            input.metadata().label(),
            input.metadata().description(),
            input.metadata().owner(),
            input.metadata().tags(),
            input.metadata().aliases());
    return new SemanticModel.Metric(1, "metric", metadata, input.spec(), null);
  }

  private String path(SemanticModel.Metric metric) {
    String domain = metric.metadata().domain();
    if (domain == null || !domain.matches(IDENTIFIER))
      throw new IllegalArgumentException(
          "Metric domain must be a safe PostgreSQL schema identifier");
    return "domains/" + domain + "/metrics/" + metric.metadata().name() + ".yaml";
  }

  private String serialize(SemanticModel.Metric metric) {
    try {
      return yaml.writeValueAsString(
          new MetricDocument(1, "metric", metric.metadata(), metric.spec()));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize metric", e);
    }
  }

  private void validateAndApply(Map<String, String> upserts, Set<String> deletions) {
    if (!(repository instanceof MutableModelRepository mutable))
      throw new ResponseStatusException(
          HttpStatus.METHOD_NOT_ALLOWED,
          "Direct metric CRUD is disabled for GitLab mode; submit a model change instead");
    ModelRevision current = repository.loadDefaultRevision();
    Map<String, String> candidate = new TreeMap<>(current.files());
    deletions.forEach(candidate::remove);
    candidate.putAll(upserts);
    SemanticModel parsed = parser.parse(new ModelRevision("candidate", candidate));
    var validation = validator.validate(parsed);
    if (!validation.valid()) {
      var first = validation.errors().getFirst();
      throw new IllegalArgumentException(first.path() + ": " + first.message());
    }
    mutable.apply(upserts, deletions);
    var status = catalog.reload();
    if (!status.healthy())
      throw new IllegalStateException(
          "Metric was persisted but the active model could not reload: " + status.message());
  }

  public record MetricInput(SemanticModel.Metadata metadata, SemanticModel.MetricSpec spec) {}

  private record MetricDocument(
      int version, String kind, SemanticModel.Metadata metadata, SemanticModel.MetricSpec spec) {}
}
