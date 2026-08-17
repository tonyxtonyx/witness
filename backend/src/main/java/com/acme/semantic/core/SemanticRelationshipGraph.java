package com.acme.semantic.core;

import com.acme.semantic.model.SemanticModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic relationship graph used by context, planning, and lineage. */
public final class SemanticRelationshipGraph {
  private final SemanticModel model;
  private final Map<String, List<Edge>> adjacency;

  public SemanticRelationshipGraph(SemanticModel model) {
    this.model = model;
    this.adjacency = build(model);
  }

  public PathResult uniqueShortestPath(String from, String to) {
    if (from.equals(to)) return new PathResult(List.of(), false);
    record State(String node, List<Edge> edges, Set<String> visited) {}
    ArrayDeque<State> queue = new ArrayDeque<>();
    queue.add(new State(from, List.of(), Set.of(from)));
    List<List<Edge>> matches = new ArrayList<>();
    int shortest = Integer.MAX_VALUE;
    while (!queue.isEmpty()) {
      State state = queue.removeFirst();
      if (state.edges().size() >= shortest) continue;
      for (Edge edge : adjacency.getOrDefault(state.node(), List.of())) {
        String next = edge.other(state.node());
        if (state.visited().contains(next)) continue;
        List<Edge> path = new ArrayList<>(state.edges());
        path.add(edge);
        if (next.equals(to)) {
          shortest = path.size();
          matches.add(List.copyOf(path));
          continue;
        }
        Set<String> visited = new HashSet<>(state.visited());
        visited.add(next);
        queue.addLast(new State(next, List.copyOf(path), Set.copyOf(visited)));
      }
    }
    int shortestLength = shortest;
    matches.removeIf(path -> path.size() != shortestLength);
    if (matches.isEmpty()) return new PathResult(null, false);
    matches.sort(Comparator.comparing(this::pathKey));
    return new PathResult(matches.getFirst(), matches.size() > 1);
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
        if (!model.objects().containsKey(relationship.targetObject())) continue;
        Edge edge =
            new Edge(source.metadata().name(), relationship.targetObject(), relationship);
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

  public record PathResult(List<Edge> edges, boolean ambiguous) {
    public Optional<List<Edge>> path() {
      return Optional.ofNullable(edges);
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
