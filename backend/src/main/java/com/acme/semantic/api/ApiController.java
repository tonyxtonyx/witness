package com.acme.semantic.api;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.gitlab.*;
import com.acme.semantic.model.*;
import com.acme.semantic.validation.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final SemanticCatalog catalog;
  private final ChangeService changes;
  private final ModelParser parser;
  private final ModelValidator validator;
  private final MetricCrudService metricCrud;
  private final ObjectCrudService objectCrud;

  public ApiController(
      SemanticCatalog catalog,
      ChangeService changes,
      ModelParser parser,
      ModelValidator validator,
      MetricCrudService metricCrud,
      ObjectCrudService objectCrud) {
    this.catalog = catalog;
    this.changes = changes;
    this.parser = parser;
    this.validator = validator;
    this.metricCrud = metricCrud;
    this.objectCrud = objectCrud;
  }

  @GetMapping("/objects")
  public Collection<SemanticModel.SemanticObject> objects(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String domain) {
    return catalog.model().objects().values().stream()
        .filter(o -> matches(o.metadata(), q, owner, tag))
        .filter(o -> domain == null || domain.equals(catalog.model().domain(o)))
        .toList();
  }

  @GetMapping("/objects/{name}")
  public SemanticModel.SemanticObject object(@PathVariable String name) {
    var object = catalog.model().objects().get(name);
    if (object == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown object: " + name);
    return object;
  }

  @PostMapping("/objects")
  @ResponseStatus(HttpStatus.CREATED)
  public SemanticModel.SemanticObject createObject(
      @RequestBody ObjectCrudService.ObjectInput input) {
    return objectCrud.create(input);
  }

  @PutMapping("/objects/{name}")
  public SemanticModel.SemanticObject updateObject(
      @PathVariable String name, @RequestBody ObjectCrudService.ObjectInput input) {
    return objectCrud.update(name, input);
  }

  @DeleteMapping("/objects/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteObject(@PathVariable String name) {
    objectCrud.delete(name);
  }

  @GetMapping("/metrics")
  public Collection<SemanticModel.Metric> metrics(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String object,
      @RequestParam(required = false) String domain) {
    return catalog.model().metrics().values().stream()
        .filter(m -> matches(m.metadata(), q, owner, tag))
        .filter(m -> object == null || object.equals(m.spec().baseObject()))
        .filter(m -> domain == null || domain.equals(catalog.model().domain(m)))
        .toList();
  }

  @GetMapping("/metrics/{name}")
  public SemanticModel.Metric metric(@PathVariable String name) {
    var metric = catalog.model().metrics().get(name);
    if (metric == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown metric: " + name);
    return metric;
  }

  @PostMapping("/metrics")
  @ResponseStatus(HttpStatus.CREATED)
  public SemanticModel.Metric createMetric(@RequestBody MetricCrudService.MetricInput input) {
    return metricCrud.create(input);
  }

  @PutMapping("/metrics/{name}")
  public SemanticModel.Metric updateMetric(
      @PathVariable String name, @RequestBody MetricCrudService.MetricInput input) {
    return metricCrud.update(name, input);
  }

  @DeleteMapping("/metrics/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteMetric(@PathVariable String name) {
    metricCrud.delete(name);
  }

  @GetMapping("/relationships")
  public List<RelationshipView> relationships() {
    return catalog.model().objects().values().stream()
        .flatMap(
            o ->
                o.spec().relationships().stream()
                    .map(r -> new RelationshipView(o.metadata().name(), r)))
        .toList();
  }

  @GetMapping("/graph")
  public Graph graph() {
    return new Graph(
        catalog.model().objects().values().stream()
            .map(o -> new Node(o.metadata().name(), o.metadata().label(), o.metadata().tags()))
            .toList(),
        relationships());
  }

  @GetMapping("/model/status")
  public SemanticCatalog.Status status() {
    return catalog.status();
  }

  @PostMapping("/model/reload")
  public SemanticCatalog.Status reload() {
    return catalog.reload();
  }

  @PostMapping("/model/validate")
  public ValidationResult validate(@RequestBody Map<String, String> files) {
    try {
      SemanticModel m = parser.parse(new ModelRevision("candidate", files));
      return validator.validate(m);
    } catch (ModelParseException e) {
      return ValidationResult.of(
          List.of(
              new ValidationError(
                  e.file(), e.path(), e.code(), e.getMessage(), ValidationError.Severity.ERROR)));
    }
  }

  @PostMapping("/changes/validate")
  public ChangeService.Preview validateChanges(@Valid @RequestBody ChangeSet set) {
    return changes.validate(set);
  }

  @PostMapping("/changes/submit")
  public ChangeResult submit(@Valid @RequestBody ChangeSet set) {
    return changes.submit(set);
  }

  private boolean matches(SemanticModel.Metadata m, String q, String owner, String tag) {
    String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
    boolean text =
        needle == null
            || List.of(
                    m.name(),
                    Objects.toString(m.label(), ""),
                    Objects.toString(m.description(), ""),
                    String.join(" ", m.tags()))
                .stream()
                .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(needle));
    return text
        && (owner == null || owner.equals(m.owner()))
        && (tag == null || m.tags().contains(tag));
  }

  public record RelationshipView(String sourceObject, SemanticModel.Relationship relationship) {}

  public record Node(String id, String label, List<String> tags) {}

  public record Graph(List<Node> nodes, List<RelationshipView> edges) {}
}
