package com.acme.semantic.core;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.model.SemanticModel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SemanticMetadataService {
  private final SemanticCatalog catalog;
  private final SemanticAccessPolicy policy;

  public SemanticMetadataService(SemanticCatalog catalog, SemanticAccessPolicy policy) {
    this.catalog = catalog;
    this.policy = policy;
  }

  public SearchPage search(SemanticPrincipal principal, SearchRequest request) {
    policy.requireAuthenticated(principal);
    SemanticModel model = catalog.model();
    int start = decodeCursor(request.cursor(), model.revision());
    String query = Objects.requireNonNullElse(request.query(), "").trim().toLowerCase(Locale.ROOT);
    Set<ObjectType> types =
        request.types() == null || request.types().isEmpty()
            ? Set.of(ObjectType.metric, ObjectType.dimension, ObjectType.semantic_object)
            : Set.copyOf(request.types());
    Set<String> requestedTags =
        request.tags() == null
            ? Set.of()
            : request.tags().stream()
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<SearchResult> matches = new ArrayList<>();

    if (types.contains(ObjectType.semantic_object)) {
      for (SemanticModel.SemanticObject object : model.objects().values()) {
        if (!policy.canReadObject(principal, model, object)
            || !metadataFilters(model.domain(object), object.metadata(), request, requestedTags)) {
          continue;
        }
        Match match = match(query, SemanticIds.objectId(model, object), object.metadata());
        if (match != null) {
          matches.add(
              result(
                  SemanticIds.objectId(model, object),
                  ObjectType.semantic_object,
                  object.metadata(),
                  model.domain(object),
                  match));
        }
      }
    }
    if (types.contains(ObjectType.metric)) {
      for (SemanticModel.Metric metric : model.metrics().values()) {
        if (!policy.canReadMetric(principal, model, metric)
            || !metadataFilters(model.domain(metric), metric.metadata(), request, requestedTags)) {
          continue;
        }
        Match match = match(query, SemanticIds.metricId(model, metric), metric.metadata());
        if (match != null) {
          matches.add(
              result(
                  SemanticIds.metricId(model, metric),
                  ObjectType.metric,
                  metric.metadata(),
                  model.domain(metric),
                  match));
        }
      }
    }
    if (types.contains(ObjectType.dimension)) {
      for (SemanticModel.SemanticObject object : model.objects().values()) {
        for (SemanticModel.Dimension dimension : object.spec().dimensions()) {
          if (!policy.canReadDimension(principal, model, object, dimension)) continue;
          SemanticModel.Metadata metadata =
              new SemanticModel.Metadata(
                  dimension.name(),
                  model.domain(object),
                  dimension.label(),
                  dimension.description(),
                  object.metadata().owner(),
                  object.metadata().tags(),
                  List.of());
          if (!metadataFilters(model.domain(object), metadata, request, requestedTags)) continue;
          String id = SemanticIds.dimensionId(model, object, dimension);
          Match match = match(query, id, metadata);
          if (match != null) {
            matches.add(
                result(id, ObjectType.dimension, metadata, model.domain(object), match));
          }
        }
      }
    }

    matches.sort(
        Comparator.comparingDouble(SearchResult::score)
            .reversed()
            .thenComparing(result -> result.type().name())
            .thenComparing(SearchResult::id));
    int limit = request.limit();
    int end = Math.min(matches.size(), start + limit);
    List<SearchResult> page = start >= matches.size() ? List.of() : matches.subList(start, end);
    String next = end < matches.size() ? encodeCursor(model.revision(), end) : null;
    return new SearchPage(List.copyOf(page), next, model.revision());
  }

  public ObjectDefinition get(SemanticPrincipal principal, String id) {
    policy.requireAuthenticated(principal);
    SemanticModel model = catalog.model();
    List<SemanticIds.ResolvedObject> matches = SemanticIds.resolveAny(model, policy, principal, id);
    if (matches.isEmpty()) {
      throw new SemanticException(
          SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
          "The requested semantic object was not found or is not accessible",
          false,
          Map.of(),
          List.of("Use search_semantic_objects to find accessible semantic IDs"));
    }
    if (matches.size() > 1) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
          "The semantic ID is ambiguous across object types: " + id,
          false,
          Map.of("id", id),
          List.of("Use search_semantic_objects with an objectTypes filter"));
    }
    SemanticIds.ResolvedObject resolved = matches.getFirst();
    return switch (resolved.type()) {
      case "metric" -> metricDefinition(principal, model, resolved.metric());
      case "dimension" ->
          dimensionDefinition(principal, model, resolved.object(), resolved.dimension());
      default -> objectDefinition(principal, model, resolved.object());
    };
  }

  public MetricContext metricContext(SemanticPrincipal principal, String metricId) {
    policy.requireAuthenticated(principal);
    SemanticModel model = catalog.model();
    SemanticModel.Metric metric = SemanticIds.requireMetric(model, policy, principal, metricId);
    SemanticModel.SemanticObject base =
        model.resolveObject(metric.spec().baseObject(), model.domain(metric)).value();
    if (base == null || !policy.canReadObject(principal, model, base)) {
      throw new SemanticException(
          SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
          "The requested metric was not found or is not accessible");
    }
    SemanticRelationshipGraph graph = new SemanticRelationshipGraph(model);
    List<CompatibleDimension> compatible = new ArrayList<>();
    for (SemanticModel.SemanticObject object : model.objects().values()) {
      if (!policy.canReadObject(principal, model, object)) continue;
      SemanticRelationshipGraph.PathResult path =
          graph.uniqueShortestPath(model.objectId(base), model.objectId(object));
      if (path.ambiguous() || path.path().isEmpty()) continue;
      if (!graph.fanoutSafe(model.objectId(base), path.edges())) continue;
      List<String> joinPath = path.edges().stream().map(edge -> edge.relationship().name()).toList();
      for (SemanticModel.Dimension dimension : object.spec().dimensions()) {
        if (policy.canReadDimension(principal, model, object, dimension)) {
          compatible.add(
              new CompatibleDimension(
                  SemanticIds.dimensionId(model, object, dimension),
                  dimension.label(),
                  dimension.type(),
                  joinPath));
        }
      }
    }
    compatible.sort(Comparator.comparing(CompatibleDimension::id));
    List<String> grain =
        base.spec().primaryKey().stream()
            .map(
                name ->
                    SemanticIds.dimensionId(model, base, base.dimension(name).orElseThrow()))
            .toList();
    SemanticModel.Dimension defaultTime =
        base.spec().dimensions().stream().filter(this::temporal).findFirst().orElse(null);
    String defaultTimeId =
        defaultTime == null ? null : SemanticIds.dimensionId(model, base, defaultTime);
    List<Map<String, Object>> requiredFilters =
        java.util.stream.Stream.concat(
                metric.spec().filters().stream()
                    .map(
                        filter -> {
                          Map<String, Object> value = new LinkedHashMap<>();
                          SemanticModel.Dimension dimension =
                              base.dimension(filter.field()).orElseThrow();
                          value.put("member", SemanticIds.dimensionId(model, base, dimension));
                          value.put("operator", filter.operator().name());
                          value.put("values", filter.values());
                          return Map.copyOf(value);
                        }),
                policy.requiredFilters(principal, model).stream()
                    .map(
                        filter -> Map.<String, Object>of("automaticallyApplied", true)))
            .toList();
    return new MetricContext(
        new MetricSummary(
            SemanticIds.metricId(model, metric),
            metric.metadata().label(),
            metric.metadata().description(),
            aggregationName(metric.spec().aggregation()),
            metric.spec().format(),
            null,
            SemanticIds.certified(metric.metadata()),
            additivity(metric.spec().aggregation())),
        grain,
        defaultTimeId,
        defaultTime == null ? List.of() : List.of("day", "week", "month", "quarter", "year"),
        compatible,
        List.of(SemanticIds.objectId(model, base)),
        requiredFilters,
        List.of(metric.metadata().owner()),
        Map.of(),
        List.of("Multi-currency conversion and freshness metadata are not modeled in schema v1"),
        model.revision());
  }

  private ObjectDefinition objectDefinition(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object) {
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put(
        "primaryKey",
        object.spec().primaryKey().stream()
            .map(name -> SemanticIds.dimensionId(model, object, object.dimension(name).orElseThrow()))
            .toList());
    definition.put(
        "dimensions",
        object.spec().dimensions().stream()
            .filter(dimension -> policy.canReadDimension(principal, model, object, dimension))
            .map(dimension -> SemanticIds.dimensionId(model, object, dimension))
            .sorted()
            .toList());
    definition.put(
        "metrics",
        model.metrics().values().stream()
            .filter(
                metric ->
                    model.resolveObject(metric.spec().baseObject(), model.domain(metric)).value()
                        == object)
            .filter(metric -> policy.canReadMetric(principal, model, metric))
            .map(metric -> SemanticIds.metricId(model, metric))
            .sorted()
            .toList());
    definition.put(
        "relationships",
        object.spec().relationships().stream()
            .filter(
                relationship ->
                    model.resolveObject(relationship.targetObject(), model.domain(object)).found())
            .filter(
                relationship ->
                    policy.canReadObject(
                        principal,
                        model,
                        model.resolveObject(relationship.targetObject(), model.domain(object)).value()))
            .map(
                relationship ->
                    Map.of(
                        "name", relationship.name(),
                        "target",
                            SemanticIds.objectId(
                                model,
                                model.resolveObject(
                                        relationship.targetObject(), model.domain(object))
                                    .value()),
                        "cardinality", relationship.cardinality().name()))
            .sorted(Comparator.comparing(value -> value.get("name").toString()))
            .toList());
    return definition(
        SemanticIds.objectId(model, object),
        ObjectType.semantic_object,
        object.metadata(),
        model.domain(object),
        definition,
        model.revision());
  }

  private ObjectDefinition metricDefinition(
      SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
    SemanticModel.SemanticObject base =
        model.resolveObject(metric.spec().baseObject(), model.domain(metric)).value();
    if (base == null || !policy.canReadObject(principal, model, base)) {
      throw new SemanticException(
          SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
          "The requested semantic object was not found or is not accessible");
    }
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("baseObject", SemanticIds.objectId(model, base));
    definition.put("aggregation", aggregationName(metric.spec().aggregation()));
    definition.put("expression", metric.spec().expression());
    definition.put("resultType", metric.spec().resultType());
    definition.put("format", metric.spec().format());
    definition.put(
        "filters",
        metric.spec().filters().stream()
            .map(
                filter ->
                    Map.of(
                        "member",
                        SemanticIds.dimensionId(
                            model, base, base.dimension(filter.field()).orElseThrow()),
                        "operator",
                        filter.operator().name(),
                        "values",
                        filter.values()))
            .toList());
    return definition(
        SemanticIds.metricId(model, metric),
        ObjectType.metric,
        metric.metadata(),
        model.domain(metric),
        definition,
        model.revision());
  }

  private ObjectDefinition dimensionDefinition(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object,
      SemanticModel.Dimension dimension) {
    if (!policy.canReadObject(principal, model, object)) {
      throw new SemanticException(
          SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
          "The requested semantic object was not found or is not accessible");
    }
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("semanticObject", SemanticIds.objectId(model, object));
    definition.put("dataType", dimension.type());
    definition.put("role", "dimension");
    definition.put("nullable", !Boolean.FALSE.equals(dimension.nullable()));
    SemanticModel.Metadata metadata =
        new SemanticModel.Metadata(
            dimension.name(),
            model.domain(object),
            dimension.label(),
            dimension.description(),
            object.metadata().owner(),
            object.metadata().tags(),
            List.of());
    return definition(
        SemanticIds.dimensionId(model, object, dimension),
        ObjectType.dimension,
        metadata,
        model.domain(object),
        definition,
        model.revision());
  }

  private ObjectDefinition definition(
      String id,
      ObjectType type,
      SemanticModel.Metadata metadata,
      String domain,
      Map<String, Object> definition,
      String revision) {
    return new ObjectDefinition(
        id,
        type,
        metadata.name(),
        metadata.label(),
        metadata.description(),
        domain,
        metadata.owner(),
        metadata.tags(),
        metadata.aliases(),
        SemanticIds.certified(metadata),
        false,
        definition,
        revision,
        Map.of());
  }

  private SearchResult result(
      String id,
      ObjectType type,
      SemanticModel.Metadata metadata,
      String domain,
      Match match) {
    return new SearchResult(
        id,
        type,
        metadata.name(),
        metadata.label(),
        metadata.description(),
        domain,
        metadata.tags(),
        SemanticIds.certified(metadata),
        match.score(),
        match.reasons());
  }

  private boolean metadataFilters(
      String domain,
      SemanticModel.Metadata metadata,
      SearchRequest request,
      Set<String> tags) {
    if (request.domain() != null && !request.domain().equalsIgnoreCase(domain)) return false;
    if (request.certified() != null
        && request.certified() != SemanticIds.certified(metadata)) return false;
    Set<String> actual =
        metadata.tags().stream()
            .map(tag -> tag.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    return actual.containsAll(tags);
  }

  private Match match(String query, String id, SemanticModel.Metadata metadata) {
    if (query.isBlank()) return new Match(0.1, List.of("catalog browse"));
    String name = metadata.name().toLowerCase(Locale.ROOT);
    String title = Objects.toString(metadata.label(), "").toLowerCase(Locale.ROOT);
    String description = Objects.toString(metadata.description(), "").toLowerCase(Locale.ROOT);
    String loweredId = id.toLowerCase(Locale.ROOT);
    List<String> aliases =
        metadata.aliases().stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    List<String> reasons = new ArrayList<>();
    double score = 0;
    if (loweredId.equals(query)) {
      score = 1;
      reasons.add("exact ID match");
    } else if (name.equals(query)) {
      score = .99;
      reasons.add("exact name match");
    } else if (title.equals(query)) {
      score = .98;
      reasons.add("exact title match");
    } else if (aliases.contains(query)) {
      score = .97;
      reasons.add("exact alias match");
    } else {
      if (name.startsWith(query) || title.startsWith(query)) {
        score = Math.max(score, .86);
        reasons.add("name or title prefix match");
      }
      if (name.contains(query) || title.contains(query) || loweredId.contains(query)) {
        score = Math.max(score, .76);
        reasons.add("name, title, or ID match");
      }
      if (metadata.tags().stream().anyMatch(tag -> tag.equalsIgnoreCase(query))) {
        score = Math.max(score, .72);
        reasons.add("exact tag match");
      }
      if (aliases.stream().anyMatch(alias -> alias.contains(query))) {
        score = Math.max(score, .70);
        reasons.add("alias match");
      }
      if (description.contains(query)) {
        score = Math.max(score, .55);
        reasons.add("description match");
      }
    }
    return score == 0 ? null : new Match(score, List.copyOf(reasons));
  }

  private int decodeCursor(String cursor, String revision) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      String decoded =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int split = decoded.lastIndexOf('\n');
      if (split <= 0 || !decoded.substring(0, split).equals(revision)) throw new Exception();
      int offset = Integer.parseInt(decoded.substring(split + 1));
      if (offset < 0) throw new Exception();
      return offset;
    } catch (Exception ignored) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
          "Pagination cursor is invalid or belongs to another semantic revision");
    }
  }

  private String encodeCursor(String revision, int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((revision + "\n" + offset).getBytes(StandardCharsets.UTF_8));
  }

  private boolean temporal(SemanticModel.Dimension dimension) {
    String type = dimension.type().toLowerCase(Locale.ROOT);
    return type.startsWith("date") || type.startsWith("timestamp");
  }

  private String additivity(SemanticModel.Aggregation aggregation) {
    if (aggregation == null) return "unknown";
    return switch (aggregation) {
      case sum, count -> "additive";
      case count_distinct, avg -> "non_additive";
      default -> "semi_additive";
    };
  }

  private String aggregationName(SemanticModel.Aggregation aggregation) {
    return aggregation == null ? "unknown" : aggregation.name();
  }

  private record Match(double score, List<String> reasons) {}

  public enum ObjectType {
    metric,
    dimension,
    semantic_object
  }

  public record SearchRequest(
      String query,
      Set<ObjectType> types,
      String domain,
      Set<String> tags,
      Boolean certified,
      int limit,
      String cursor) {}

  public record SearchResult(
      String id,
      ObjectType type,
      String name,
      String title,
      String description,
      String domain,
      List<String> tags,
      boolean certified,
      double score,
      List<String> matchReasons) {}

  public record SearchPage(
      List<SearchResult> results, String nextCursor, String semanticRevision) {}

  public record ObjectDefinition(
      String id,
      ObjectType type,
      String name,
      String title,
      String description,
      String domain,
      String owner,
      List<String> tags,
      List<String> aliases,
      boolean certified,
      boolean deprecated,
      Map<String, Object> definition,
      String semanticRevision,
      Map<String, Object> freshness) {}

  public record MetricSummary(
      String id,
      String title,
      String description,
      String metricType,
      String unit,
      String defaultCurrency,
      boolean certified,
      String additivity) {}

  public record CompatibleDimension(
      String id, String title, String type, List<String> joinPath) {}

  public record MetricContext(
      MetricSummary metric,
      List<String> grain,
      String defaultTimeDimension,
      List<String> supportedTimeGranularities,
      List<CompatibleDimension> compatibleDimensions,
      List<String> entities,
      List<Map<String, Object>> requiredFilters,
      List<String> owners,
      Map<String, Object> freshness,
      List<String> knownLimitations,
      String semanticRevision) {}
}
