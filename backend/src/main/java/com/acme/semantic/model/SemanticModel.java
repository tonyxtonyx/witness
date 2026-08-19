package com.acme.semantic.model;

import java.time.Instant;
import java.util.*;

public record SemanticModel(
    Project project,
    Map<String, SemanticObject> objects,
    Map<String, Metric> metrics,
    String revision,
    Instant loadedAt) {
  public SemanticModel {
    LinkedHashMap<String, SemanticObject> qualifiedObjects = new LinkedHashMap<>();
    for (SemanticObject object : objects.values()) {
      String id = qualifiedId(project, object.metadata());
      if (qualifiedObjects.putIfAbsent(id, object) != null)
        throw new IllegalArgumentException("Duplicate object " + id);
    }
    LinkedHashMap<String, Metric> qualifiedMetrics = new LinkedHashMap<>();
    for (Metric metric : metrics.values()) {
      String id = qualifiedId(project, metric.metadata());
      if (qualifiedMetrics.putIfAbsent(id, metric) != null)
        throw new IllegalArgumentException("Duplicate metric " + id);
    }
    objects = Collections.unmodifiableMap(qualifiedObjects);
    metrics = Collections.unmodifiableMap(qualifiedMetrics);
  }

  public String domain(Metadata metadata) {
    return metadata.domain() == null || metadata.domain().isBlank()
        ? project.spec().semanticSchema()
        : metadata.domain();
  }

  public String domain(SemanticObject object) {
    return domain(object.metadata());
  }

  public String domain(Metric metric) {
    return domain(metric.metadata());
  }

  public String objectId(SemanticObject object) {
    return domain(object) + "." + object.metadata().name();
  }

  public String metricId(Metric metric) {
    return domain(metric) + "." + metric.metadata().name();
  }

  public Optional<SemanticObject> objectById(String id) {
    return Optional.ofNullable(objects.get(id));
  }

  public Optional<Metric> metricById(String id) {
    return Optional.ofNullable(metrics.get(id));
  }

  public Resolution<SemanticObject> resolveObject(String reference) {
    return resolve(reference, null, objects, SemanticObject::metadata);
  }

  public Resolution<SemanticObject> resolveObject(String reference, String preferredDomain) {
    return resolve(reference, preferredDomain, objects, SemanticObject::metadata);
  }

  public Resolution<Metric> resolveMetric(String reference) {
    return resolve(reference, null, metrics, Metric::metadata);
  }

  public Resolution<Metric> resolveMetric(String reference, String preferredDomain) {
    return resolve(reference, preferredDomain, metrics, Metric::metadata);
  }

  public Set<String> domains() {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    objects.values().forEach(object -> result.add(domain(object)));
    metrics.values().forEach(metric -> result.add(domain(metric)));
    if (result.isEmpty()) result.add(project.spec().semanticSchema());
    return Collections.unmodifiableSet(result);
  }

  private <T> Resolution<T> resolve(
      String reference,
      String preferredDomain,
      Map<String, T> values,
      java.util.function.Function<T, Metadata> metadata) {
    if (reference == null || reference.isBlank()) return new Resolution<>(null, List.of());
    if (reference.contains(".")) {
      T value = values.get(reference);
      return new Resolution<>(value, value == null ? List.of() : List.of(reference));
    }
    if (preferredDomain != null && !preferredDomain.isBlank()) {
      String preferred = preferredDomain + "." + reference;
      T value = values.get(preferred);
      if (value != null) return new Resolution<>(value, List.of(preferred));
    }
    List<Map.Entry<String, T>> matches =
        values.entrySet().stream()
            .filter(entry -> metadata.apply(entry.getValue()).name().equals(reference))
            .sorted(Map.Entry.comparingByKey())
            .toList();
    return matches.size() == 1
        ? new Resolution<>(matches.getFirst().getValue(), List.of(matches.getFirst().getKey()))
        : new Resolution<>(null, matches.stream().map(Map.Entry::getKey).toList());
  }

  private static String qualifiedId(Project project, Metadata metadata) {
    String domain =
        metadata.domain() == null || metadata.domain().isBlank()
            ? project.spec().semanticSchema()
            : metadata.domain();
    return domain + "." + metadata.name();
  }

  public record Resolution<T>(T value, List<String> candidates) {
    public Resolution {
      candidates = List.copyOf(candidates);
    }

    public boolean found() {
      return value != null;
    }

    public boolean ambiguous() {
      return value == null && candidates.size() > 1;
    }
  }

  public record Metadata(
      String name,
      String domain,
      String label,
      String description,
      String owner,
      List<String> tags,
      List<String> aliases) {
    public Metadata {
      tags = tags == null ? List.of() : List.copyOf(tags);
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public Metadata(
        String name,
        String domain,
        String label,
        String description,
        String owner,
        List<String> tags) {
      this(name, domain, label, description, owner, tags, List.of());
    }
  }

  public record Project(int version, String kind, Metadata metadata, ProjectSpec spec) {}

  public record ProjectSpec(String semanticSchema, TrinoDefaults trino) {}

  public record TrinoDefaults(String defaultCatalog, String defaultSchema) {}

  public record Source(String catalog, String schema, String table, String select) {
    public Source(String catalog, String schema, String table) {
      this(catalog, schema, table, null);
    }

    public boolean derived() {
      return select != null && !select.isBlank();
    }
  }

  public record Dimension(
      String name, String label, String description, String type, String sql, Boolean nullable) {}

  public record Relationship(
      String name,
      String targetObject,
      List<String> sourceFields,
      List<String> targetFields,
      Cardinality cardinality,
      JoinType defaultJoinType) {
    public Relationship {
      sourceFields = sourceFields == null ? List.of() : List.copyOf(sourceFields);
      targetFields = targetFields == null ? List.of() : List.copyOf(targetFields);
    }
  }

  public record ObjectSpec(
      Source source,
      List<String> primaryKey,
      List<Dimension> dimensions,
      List<Relationship> relationships) {
    public ObjectSpec {
      primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
      dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
      relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }
  }

  public record SemanticObject(
      int version, String kind, Metadata metadata, ObjectSpec spec, String file) {
    public Optional<Dimension> dimension(String name) {
      return spec.dimensions.stream().filter(d -> d.name.equalsIgnoreCase(name)).findFirst();
    }
  }

  public record MetricFilter(String field, FilterOperator operator, List<Object> values) {
    public MetricFilter {
      values = values == null ? List.of() : List.copyOf(values);
    }
  }

  public record MetricSpec(
      String baseObject,
      Aggregation aggregation,
      String expression,
      String resultType,
      String format,
      List<MetricFilter> filters) {
    public MetricSpec {
      filters = filters == null ? List.of() : List.copyOf(filters);
    }
  }

  public record Metric(int version, String kind, Metadata metadata, MetricSpec spec, String file) {}

  public enum Aggregation {
    sum,
    count,
    count_distinct,
    min,
    max,
    avg,
    custom
  }

  public enum Cardinality {
    one_to_one,
    one_to_many,
    many_to_one,
    many_to_many
  }

  public enum JoinType {
    inner,
    left,
    right,
    full
  }

  public enum FilterOperator {
    eq,
    neq,
    in,
    not_in,
    gt,
    gte,
    lt,
    lte,
    is_null,
    is_not_null
  }
}
