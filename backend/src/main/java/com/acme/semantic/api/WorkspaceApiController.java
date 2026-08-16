package com.acme.semantic.api;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.*;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.execution.*;
import com.acme.semantic.gitlab.*;
import com.acme.semantic.model.SemanticModel;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceApiController {
  private static final Logger log = LoggerFactory.getLogger(WorkspaceApiController.class);
  private static final ModelExpressionPolicy MODEL_EXPRESSIONS = new ModelExpressionPolicy();
  private final SemanticCatalog catalog;
  private final SemanticSqlCompiler compiler;
  private final QueryExecutor executor;
  private final ModelRepository repository;
  private final ChangeService changes;
  private final SemanticProperties properties;

  public WorkspaceApiController(
      SemanticCatalog catalog,
      SemanticSqlCompiler compiler,
      QueryExecutor executor,
      ModelRepository repository,
      ChangeService changes,
      SemanticProperties properties) {
    this.catalog = catalog;
    this.compiler = compiler;
    this.executor = executor;
    this.repository = repository;
    this.changes = changes;
    this.properties = properties;
  }

  @GetMapping("/model")
  public ModelView model() {
    SemanticModel model = catalog.model();
    List<DomainView> domains =
        model.domains().stream()
            .map(
                domain ->
                    new DomainView(
                        domain,
                        humanize(domain),
                        model.objects().values().stream()
                            .filter(o -> model.domain(o).equals(domain))
                            .count(),
                        model.metrics().values().stream()
                            .filter(m -> model.domain(m).equals(domain))
                            .count()))
            .toList();
    return new ModelView(
        model.project().metadata().name(),
        model.revision(),
        model.loadedAt(),
        properties.gitlab().enabled() ? "governed" : "local",
        domains,
        model.objects().size(),
        model.metrics().size(),
        model.objects().values().stream().mapToLong(o -> o.spec().relationships().size()).sum());
  }

  @GetMapping("/objects/{name}/source")
  public ObjectSource source(@PathVariable String name) {
    SemanticModel.SemanticObject object =
        Optional.ofNullable(catalog.model().objects().get(name))
            .orElseThrow(() -> new IllegalArgumentException("Unknown object: " + name));
    String yaml = repository.loadDefaultRevision().files().get(object.file());
    var source = object.spec().source();
    return new ObjectSource(
        object.file(),
        yaml,
        source.derived()
            ? "Derived SELECT (governed SQL)"
            : source.catalog() + "." + source.schema() + "." + source.table(),
        object.spec().dimensions().stream().map(d -> new LineageField(d.name(), d.sql())).toList());
  }

  @PostMapping("/query")
  public ResponseEntity<?> query(@RequestBody QueryRequest request) {
    long started = System.nanoTime();
    try {
      CompiledQuery compiled = compiler.compile(request.sql(), catalog.model());
      if (compiled.parameters().size() != request.parameters().size())
        return ResponseEntity.badRequest()
            .body(
                new QueryError(
                    "07001",
                    "Expected "
                        + compiled.parameters().size()
                        + " query parameters but received "
                        + request.parameters().size(),
                    "Provide one value for every ? placeholder."));
      QueryResult result = executor.execute(compiled, request.parameters());
      long elapsed = (System.nanoTime() - started) / 1_000_000;
      return ResponseEntity.ok(
          new QueryResponse(
              result.columns(),
              result.rows(),
              result.rows().size(),
              elapsed,
              compiled.trinoSql(),
              result.queryId()));
    } catch (SqlCompilationException e) {
      String hint =
          e.getMessage().contains("belongs to domain schema")
                  || e.getMessage().contains("Unknown semantic object")
              ? "Reference a registered semantic object such as retail.orders."
              : "Use the supported read-only SELECT subset.";
      return ResponseEntity.badRequest().body(new QueryError(e.sqlState(), e.getMessage(), hint));
    } catch (QueryExecutionException e) {
      log.warn("Trino query failed correlationId={}", MDC.get("correlationId"), e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(
              new QueryError(
                  e.sqlState(),
                  "Query execution failed",
                  "Check the correlation ID or contact the Witness operator."));
    }
  }

  @PostMapping("/validate")
  public ValidationResponse validate(@RequestBody ValidationRequest request) {
    List<FieldIssue> issues = new ArrayList<>();
    if (request.expression() == null || request.expression().isBlank())
      issues.add(new FieldIssue("expression", "REQUIRED", "Expression is required", "ERROR"));
    SemanticModel.SemanticObject object = catalog.model().objects().get(request.objectId());
    if (object == null)
      issues.add(
          new FieldIssue("objectId", "UNKNOWN_OBJECT", "Base object does not exist", "ERROR"));
    if (issues.isEmpty())
      try {
        MODEL_EXPRESSIONS.renderMetric(
            request.expression(),
            object.metadata().name(),
            "custom".equalsIgnoreCase(request.aggregation())
                ? ModelExpressionPolicy.ExpressionKind.CUSTOM_AGGREGATE
                : ModelExpressionPolicy.ExpressionKind.METRIC_VALUE,
            object);
      } catch (SqlCompilationException e) {
        issues.add(new FieldIssue("expression", e.sqlState(), e.getMessage(), "ERROR"));
      }
    return new ValidationResponse(issues.isEmpty(), issues);
  }

  @GetMapping("/search")
  public List<SearchResult> search(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String domain,
      @RequestParam(defaultValue = "false") boolean verifiedOnly) {
    String needle = q.toLowerCase(Locale.ROOT);
    SemanticModel model = catalog.model();
    List<SearchResult> out = new ArrayList<>();
    if (type == null || type.equals("object"))
      for (var object : model.objects().values()) {
        boolean verified = verified(object.metadata());
        String objectDomain = model.domain(object);
        if ((domain == null || domain.equals(objectDomain))
            && (!verifiedOnly || verified)
            && matches(object.metadata(), needle))
          out.add(
              new SearchResult(
                  "object",
                  object.metadata().name(),
                  object.metadata().label(),
                  objectDomain,
                  "/objects/" + object.metadata().name(),
                  verified,
                  object.metadata().owner(),
                  object.metadata().description()));
      }
    if (type == null || type.equals("dimension"))
      for (var object : model.objects().values()) {
        String objectDomain = model.domain(object);
        for (var dimension : object.spec().dimensions()) {
          boolean verified =
              dimension.description() != null
                  && !dimension.description().isBlank()
                  && verified(object.metadata());
          String haystack =
              String.join(
                      " ",
                      dimension.name(),
                      Objects.toString(dimension.label(), ""),
                      Objects.toString(dimension.description(), ""),
                      dimension.type(),
                      dimension.sql())
                  .toLowerCase(Locale.ROOT);
          if ((domain == null || domain.equals(objectDomain))
              && (!verifiedOnly || verified)
              && (needle.isBlank() || haystack.contains(needle)))
            out.add(
                new SearchResult(
                    "dimension",
                    dimension.name(),
                    dimension.label(),
                    objectDomain,
                    "/objects/" + object.metadata().name() + "?tab=dimensions",
                    verified,
                    object.metadata().owner(),
                    dimension.description()));
        }
      }
    if (type == null || type.equals("metric"))
      for (var metric : model.metrics().values()) {
        boolean verified = verified(metric.metadata());
        String metricDomain = model.domain(metric);
        if ((domain == null || domain.equals(metricDomain))
            && (!verifiedOnly || verified)
            && matches(metric.metadata(), needle))
          out.add(
              new SearchResult(
                  "metric",
                  metric.metadata().name(),
                  metric.metadata().label(),
                  metricDomain,
                  "/objects/" + metric.spec().baseObject() + "/metrics/" + metric.metadata().name(),
                  verified,
                  metric.metadata().owner(),
                  metric.metadata().description()));
      }
    return out;
  }

  @PostMapping("/changes")
  public ChangeResult submit(@RequestBody ChangeSet set) {
    return changes.submit(set);
  }

  private boolean matches(SemanticModel.Metadata metadata, String q) {
    return q.isBlank()
        || String.join(
                " ",
                metadata.name(),
                Objects.toString(metadata.label(), ""),
                Objects.toString(metadata.description(), ""),
                Objects.toString(metadata.owner(), ""),
                String.join(" ", metadata.tags()))
            .toLowerCase(Locale.ROOT)
            .contains(q);
  }

  private boolean verified(SemanticModel.Metadata metadata) {
    return metadata.description() != null
        && !metadata.description().isBlank()
        && metadata.owner() != null
        && !metadata.owner().isBlank();
  }

  private String humanize(String value) {
    String text = value.replace('_', ' ');
    return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  public record DomainView(String key, String label, long objectCount, long metricCount) {}

  public record ModelView(
      String name,
      String revision,
      java.time.Instant loadedAt,
      String governanceMode,
      List<DomainView> domains,
      int objectCount,
      int metricCount,
      long relationshipCount) {}

  public record LineageField(String dimension, String expression) {}

  public record ObjectSource(
      String file, String yaml, String physicalSource, List<LineageField> fields) {}

  public record QueryRequest(String sql, List<Object> parameters) {
    public QueryRequest {
      parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
  }

  public record QueryResponse(
      List<QueryResult.Column> columns,
      List<List<Object>> rows,
      int rowCount,
      long elapsedMs,
      String compiledTrinoSql,
      String queryId) {}

  public record QueryError(String code, String message, String hint) {}

  public record ValidationRequest(String expression, String objectId, String aggregation) {}

  public record FieldIssue(String field, String code, String message, String severity) {}

  public record ValidationResponse(boolean valid, List<FieldIssue> errors) {}

  public record SearchResult(
      String type,
      String name,
      String label,
      String domain,
      String path,
      boolean verified,
      String owner,
      String snippet) {}
}
