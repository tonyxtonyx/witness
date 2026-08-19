package com.acme.semantic.core;

import com.acme.semantic.model.SemanticModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic relationship graph used by context, planning, and lineage. */
public final class SemanticRelationshipGraph {
  private static final int MAX_EXPANDED_STATES = 10_000;
  private static final int MAX_CANDIDATE_PATHS = 5;
  private final SemanticModel model;
  private final Map<String, List<Edge>> adjacency;

  public SemanticRelationshipGraph(SemanticModel model) {
    this.model = model;
    this.adjacency = build(model);
  }

  public PathResult uniqueShortestPath(String from, String to) {
    if (from.equals(to)) return new PathResult(List.of(), false, List.of(List.of()));
    ArrayDeque<String> queue = new ArrayDeque<>();
    Map<String, Integer> distances = new HashMap<>();
    Map<String, List<List<Edge>>> paths = new HashMap<>();
    queue.add(from);
    distances.put(from, 0);
    paths.put(from, List.of(List.of()));
    Integer shortest = null;
    int expanded = 0;
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      int distance = distances.get(current);
      if (shortest != null && distance >= shortest) break;
      for (Edge edge : adjacency.getOrDefault(current, List.of())) {
        if (++expanded > MAX_EXPANDED_STATES)
          throw new SemanticException(
              SemanticErrorCode.QUERY_LIMIT_EXCEEDED,
              "Relationship path search exceeded "
                  + MAX_EXPANDED_STATES
                  + " expanded states");
        String next = edge.other(current);
        int nextDistance = distance + 1;
        Integer knownDistance = distances.get(next);
        if (knownDistance != null && knownDistance < nextDistance) continue;
        List<List<Edge>> candidates = new ArrayList<>();
        for (List<Edge> currentPath : paths.get(current)) {
          List<Edge> path = new ArrayList<>(currentPath);
          path.add(edge);
          candidates.add(List.copyOf(path));
        }
        if (knownDistance == null) {
          distances.put(next, nextDistance);
          paths.put(next, limitedDistinct(candidates));
          queue.addLast(next);
          if (next.equals(to)) shortest = nextDistance;
        } else {
          List<List<Edge>> combined = new ArrayList<>(paths.get(next));
          combined.addAll(candidates);
          paths.put(next, limitedDistinct(combined));
        }
      }
    }
    if (!paths.containsKey(to)) return new PathResult(null, false, List.of());
    List<List<Edge>> candidates = paths.get(to);
    return new PathResult(candidates.getFirst(), candidates.size() > 1, candidates);
  }

  public List<Edge> edgesFrom(String object) {
    return adjacency.getOrDefault(object, List.of());
  }

  public boolean fanoutSafe(String metricObject, List<Edge> path) {
    String current = metricObject;
    for (Edge edge : path) {
      SemanticModel.Relationship relationship = edge.relationship();
      boolean forward = current.equals(edge.sourceObject());
      boolean reverse = current.equals(edge.targetObject());
      if (!forward && !reverse) return false;
      boolean unsafe = switch (relationship.cardinality()) {
        case one_to_one -> false;
        case many_to_one -> reverse;
        case one_to_many -> forward;
        case many_to_many -> true;
      };
      if (unsafe) return false;
      current = edge.other(current);
    }
    return true;
  }

  public List<Edge> edges() {
    return adjacency.values().stream()
        .flatMap(List::stream)
        .distinct()
        .sorted(Comparator.comparing(Edge::id))
        .toList();
  }

  private Map<String, List<Edge>> build(SemanticModel model) {
    Map<String, List<Edge>> out = new LinkedHashMap<>();
    for (SemanticModel.SemanticObject source : model.objects().values()) {
      for (SemanticModel.Relationship relationship : source.spec().relationships()) {
        SemanticModel.SemanticObject target =
            model.resolveObject(relationship.targetObject(), model.domain(source)).value();
        if (target == null) continue;
        Edge edge =
            new Edge(model.objectId(source), model.objectId(target), relationship);
        out.computeIfAbsent(edge.sourceObject(), ignored -> new ArrayList<>()).add(edge);
        out.computeIfAbsent(edge.targetObject(), ignored -> new ArrayList<>()).add(edge);
      }
    }
    Map<String, List<Edge>> immutable = new HashMap<>();
    out.forEach(
        (key, value) ->
            immutable.put(
                key,
                value.stream().sorted(Comparator.comparing(Edge::id)).toList()));
    return Map.copyOf(immutable);
  }

  private String pathKey(List<Edge> path) {
    return path.stream().map(Edge::id).reduce((left, right) -> left + ">" + right).orElse("");
  }

  private List<List<Edge>> limitedDistinct(List<List<Edge>> paths) {
    Map<String, List<Edge>> distinct = new LinkedHashMap<>();
    paths.stream()
        .sorted(Comparator.comparing(this::pathKey))
        .forEach(path -> distinct.putIfAbsent(pathKey(path), path));
    return distinct.values().stream().limit(MAX_CANDIDATE_PATHS).toList();
  }

  public static final class PathResult {
    private final List<Edge> edges;
    private final boolean ambiguous;
    private final List<List<Edge>> candidatePaths;

    public PathResult(List<Edge> edges, boolean ambiguous) {
      this(edges, ambiguous, edges == null ? List.of() : List.of(edges));
    }

    private PathResult(
        List<Edge> edges, boolean ambiguous, List<List<Edge>> candidatePaths) {
      this.edges = edges;
      this.ambiguous = ambiguous;
      this.candidatePaths = List.copyOf(candidatePaths);
    }

    public List<Edge> edges() {
      return edges;
    }

    public boolean ambiguous() {
      return ambiguous;
    }

    public Optional<List<Edge>> path() {
      return Optional.ofNullable(edges);
    }

    public List<List<Edge>> candidatePaths() {
      return candidatePaths;
    }

    @Override
    public boolean equals(Object value) {
      if (this == value) return true;
      if (!(value instanceof PathResult other)) return false;
      return ambiguous == other.ambiguous && Objects.equals(edges, other.edges);
    }

    @Override
    public int hashCode() {
      return Objects.hash(edges, ambiguous);
    }

    @Override
    public String toString() {
      return "PathResult[edges=" + edges + ", ambiguous=" + ambiguous + "]";
    }
  }

  public record Edge(
      String sourceObject,
      String targetObject,
      SemanticModel.Relationship relationship) {
    public String id() {
      return sourceObject + "." + relationship.name() + "." + targetObject;
    }

    public String other(String object) {
      if (sourceObject.equals(object)) return targetObject;
      if (targetObject.equals(object)) return sourceObject;
      throw new IllegalArgumentException("Object is not incident to relationship: " + object);
    }
  }
}
