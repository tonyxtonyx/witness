package com.acme.semantic.api;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.gitlab.ModelRepository;
import com.acme.semantic.gitlab.MutableModelRepository;
import com.acme.semantic.model.ModelParser;
import com.acme.semantic.model.ModelRevision;
import com.acme.semantic.model.SemanticModel;
import com.acme.semantic.validation.ModelValidator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ObjectCrudService {
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
  public ObjectCrudService(
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

  ObjectCrudService(
      ModelRepository repository,
      SemanticCatalog catalog,
      ModelParser parser,
      ModelValidator validator) {
    this(repository, catalog, parser, validator, null, null);
  }

  public synchronized SemanticModel.SemanticObject update(
      SemanticPrincipal principal, String name, ObjectInput input) {
    access.writableObject(principal, name);
    SemanticModel.SemanticObject replacement = normalize(input);
    requireReadableRelationships(principal, replacement);
    policy.requireWriteDomain(principal, catalog.model().domain(replacement));
    return update(name, input);
  }

  public synchronized SemanticModel.SemanticObject create(
      SemanticPrincipal principal, ObjectInput input) {
    SemanticModel.SemanticObject object = normalize(input);
    requireReadableRelationships(principal, object);
    policy.requireWriteDomain(principal, catalog.model().domain(object));
    return create(input);
  }

  public synchronized void delete(SemanticPrincipal principal, String name) {
    access.writableObject(principal, name);
    delete(name);
  }

  public synchronized SemanticModel.SemanticObject update(String name, ObjectInput input) {
    SemanticModel.SemanticObject existing = require(name);
    SemanticModel.SemanticObject object = normalize(input);
    if (!existing.metadata().name().equals(object.metadata().name())) {
      throw new IllegalArgumentException("Object name in the path and body must match");
    }
    String path = path(object);
    Set<String> deletions = path.equals(existing.file()) ? Set.of() : Set.of(existing.file());
    validateAndApply(Map.of(path, serialize(object)), deletions);
    return catalog.model().objectById(catalog.model().objectId(object)).orElseThrow();
  }

  public synchronized SemanticModel.SemanticObject create(ObjectInput input) {
    SemanticModel.SemanticObject object = normalize(input);
    String id = catalog.model().objectId(object);
    if (catalog.model().objects().containsKey(id)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Object already exists: " + id);
    }
    String path = path(object);
    validateAndApply(Map.of(path, serialize(object)), Set.of());
    return catalog.model().objectById(id).orElseThrow();
  }

  public synchronized void delete(String name) {
    SemanticModel.SemanticObject existing = require(name);
    validateAndApply(Map.of(), Set.of(existing.file()));
  }

  private SemanticModel.SemanticObject require(String name) {
    return ApiModelResolver.object(catalog.model(), name);
  }

  private void requireReadableRelationships(
      SemanticPrincipal principal, SemanticModel.SemanticObject object) {
    for (SemanticModel.Relationship relationship : object.spec().relationships())
      access.readableObject(
          principal, relationship.targetObject(), catalog.model().domain(object));
  }

  private SemanticModel.SemanticObject normalize(ObjectInput input) {
    if (input == null || input.metadata() == null || input.spec() == null) {
      throw new IllegalArgumentException("metadata and spec are required");
    }
    String domain = catalog.model().domain(input.metadata());
    SemanticModel.Metadata metadata =
        new SemanticModel.Metadata(
            input.metadata().name(),
            domain,
            input.metadata().label(),
            input.metadata().description(),
            input.metadata().owner(),
            input.metadata().tags(),
            input.metadata().aliases());
    return new SemanticModel.SemanticObject(1, "object", metadata, input.spec(), null);
  }

  private String path(SemanticModel.SemanticObject object) {
    String name = object.metadata().name();
    String domain = object.metadata().domain();
    if (name == null || !name.matches(IDENTIFIER)) {
      throw new IllegalArgumentException("Object name must be a safe SQL identifier");
    }
    if (domain == null || !domain.matches(IDENTIFIER)) {
      throw new IllegalArgumentException(
          "Object domain must be a safe PostgreSQL schema identifier");
    }
    return "domains/" + domain + "/objects/" + name + ".yaml";
  }

  private String serialize(SemanticModel.SemanticObject object) {
    try {
      return yaml.writeValueAsString(
          new ObjectDocument(1, "object", object.metadata(), object.spec()));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize semantic object", e);
    }
  }

  private void validateAndApply(Map<String, String> upserts, Set<String> deletions) {
    if (!(repository instanceof MutableModelRepository mutable)) {
      throw new ResponseStatusException(
          HttpStatus.METHOD_NOT_ALLOWED,
          "Direct object CRUD is disabled for GitLab mode; submit a model change instead");
    }
    ModelRevision current = repository.loadDefaultRevision();
    Map<String, String> candidate = new TreeMap<>(current.files());
    deletions.forEach(candidate::remove);
    candidate.putAll(upserts);
    SemanticModel parsed = parser.parse(new ModelRevision("candidate", candidate));
    var validation = validator.validate(parsed);
    if (!validation.valid()) {
      var first = validation.errors().getFirst();
      throw new IllegalArgumentException(
          first.file() + ":" + first.path() + ": " + first.message());
    }
    mutable.apply(upserts, deletions);
    var status = catalog.reload();
    if (!status.healthy()) {
      throw new IllegalStateException(
          "Object was persisted but the active model could not reload: " + status.message());
    }
  }

  public record ObjectInput(SemanticModel.Metadata metadata, SemanticModel.ObjectSpec spec) {}

  private record ObjectDocument(
      int version, String kind, SemanticModel.Metadata metadata, SemanticModel.ObjectSpec spec) {}
}
