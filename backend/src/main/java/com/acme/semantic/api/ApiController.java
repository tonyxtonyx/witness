package com.acme.semantic.api;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.gitlab.*;
import com.acme.semantic.model.*;
import com.acme.semantic.validation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final SemanticCatalog catalog;
  private final ChangeService changes;
  private final ModelParser parser;
  private final ModelValidator validator;
  private final MetricCrudService metricCrud;
  private final ObjectCrudService objectCrud;
  private final SemanticAccessPolicy policy;

  public ApiController(
      SemanticCatalog catalog,
      ChangeService changes,
      ModelParser parser,
      ModelValidator validator,
      MetricCrudService metricCrud,
      ObjectCrudService objectCrud,
      SemanticAccessPolicy policy) {
    this.catalog = catalog;
    this.changes = changes;
    this.parser = parser;
    this.validator = validator;
    this.metricCrud = metricCrud;
    this.objectCrud = objectCrud;
    this.policy = policy;
  }

  @GetMapping("/objects")
  public Collection<SemanticModel.SemanticObject> objects(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String domain,
      HttpServletRequest request) {
    boolean physical = canViewPhysical(request);
    return catalog.model().objects().values().stream()
        .filter(o -> matches(o.metadata(), q, owner, tag))
        .filter(o -> domain == null || domain.equals(catalog.model().domain(o)))
        .map(o -> present(o, physical))
        .toList();
  }

  @GetMapping("/objects/{name}")
  public SemanticModel.SemanticObject object(
      @PathVariable String name, HttpServletRequest request) {
    var object = ApiModelResolver.object(catalog.model(), name);
    return present(object, canViewPhysical(request));
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
    SemanticModel.SemanticObject base = object == null ? null : resolveObjectFilter(object);
    return catalog.model().metrics().values().stream()
        .filter(m -> matches(m.metadata(), q, owner, tag))
        .filter(
            m ->
                base == null
                    || catalog
                            .model()
                            .resolveObject(m.spec().baseObject(), catalog.model().domain(m))
                            .value()
                        == base)
        .filter(m -> domain == null || domain.equals(catalog.model().domain(m)))
        .map(this::present)
        .toList();
  }

  @GetMapping("/metrics/{name}")
  public SemanticModel.Metric metric(@PathVariable String name) {
    return present(ApiModelResolver.metric(catalog.model(), name));
  }

  @PostMapping("/metrics")
  @ResponseStatus(HttpStatus.CREATED)
  public SemanticModel.Metric createMetric(@RequestBody MetricCrudService.MetricInput input) {
    return present(metricCrud.create(input));
  }

  @PutMapping("/metrics/{name}")
  public SemanticModel.Metric updateMetric(
      @PathVariable String name, @RequestBody MetricCrudService.MetricInput input) {
    return present(metricCrud.update(name, input));
  }

  @DeleteMapping("/metrics/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteMetric(@PathVariable String name) {
    metricCrud.delete(name);
  }

  @GetMapping("/relationships")
  public List<RelationshipView> relationships() {
    SemanticModel model = catalog.model();
    return catalog.model().objects().values().stream()
        .flatMap(
            o ->
                o.spec().relationships().stream()
                    .map(
                        r ->
                            new RelationshipView(
                                model.objectId(o),
                                new SemanticModel.Relationship(
                                    r.name(),
                                    model.objectId(
                                        model.resolveObject(r.targetObject(), model.domain(o))
                                            .value()),
                                    r.sourceFields(),
                                    r.targetFields(),
                                    r.cardinality(),
                                    r.defaultJoinType()))))
        .toList();
  }

  @GetMapping("/graph")
  public Graph graph() {
    return new Graph(
        catalog.model().objects().values().stream()
            .map(o -> new Node(catalog.model().objectId(o), o.metadata().label(), o.metadata().tags()))
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

  private boolean canViewPhysical(HttpServletRequest request) {
    SemanticPrincipal principal = ApiSecurityFilter.principal(request);
    return policy.canViewPhysicalLineage(principal);
  }

  private SemanticModel.SemanticObject resolveObjectFilter(String reference) {
    SemanticModel.Resolution<SemanticModel.SemanticObject> resolution =
        catalog.model().resolveObject(reference);
    if (resolution.ambiguous())
      throw ApiModelResolver.ambiguous("object", reference, resolution.candidates());
    return resolution.value();
  }

  private SemanticModel.SemanticObject present(
      SemanticModel.SemanticObject object, boolean physical) {
    SemanticModel.ObjectSpec spec = object.spec();
    return new SemanticModel.SemanticObject(
        object.version(),
        object.kind(),
        object.metadata(),
        new SemanticModel.ObjectSpec(
            physical ? spec.source() : new SemanticModel.Source(null, null, null, null),
            spec.primaryKey(),
            spec.dimensions(),
            spec.relationships().stream()
                .map(
                    relationship ->
                        new SemanticModel.Relationship(
                            relationship.name(),
                            catalog
                                .model()
                                .objectId(
                                    catalog
                                        .model()
                                        .resolveObject(
                                            relationship.targetObject(),
                                            catalog.model().domain(object))
                                        .value()),
                            relationship.sourceFields(),
                            relationship.targetFields(),
                            relationship.cardinality(),
                            relationship.defaultJoinType()))
                .toList()),
        object.file());
  }

  private SemanticModel.Metric present(SemanticModel.Metric metric) {
    SemanticModel.MetricSpec spec = metric.spec();
    SemanticModel.SemanticObject base =
        catalog.model().resolveObject(spec.baseObject(), catalog.model().domain(metric)).value();
    return new SemanticModel.Metric(
        metric.version(),
        metric.kind(),
        metric.metadata(),
        new SemanticModel.MetricSpec(
            catalog.model().objectId(base),
            spec.aggregation(),
            spec.expression(),
            spec.resultType(),
            spec.format(),
            spec.filters()),
        metric.file());
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
