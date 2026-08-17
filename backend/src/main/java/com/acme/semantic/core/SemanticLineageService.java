package com.acme.semantic.core;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.SemanticModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SemanticLineageService {
  private final SemanticCatalog catalog;
  private final SemanticAccessPolicy policy;
  private final SemanticProperties.Mcp config;

  public SemanticLineageService(
      SemanticCatalog catalog,
      SemanticAccessPolicy policy,
      SemanticProperties properties) {
    this.catalog = catalog;
    this.policy = policy;
    this.config = properties.mcp() == null ? SemanticProperties.Mcp.defaults() : properties.mcp();
  }

  public LineageResult lineage(SemanticPrincipal principal, LineageRequest request) {
    policy.requireAuthenticated(principal);
    SemanticModel model = catalog.model();
    List<SemanticIds.ResolvedObject> roots =
        SemanticIds.resolveAny(model, policy, principal, request.objectId());
    if (roots.size() != 1) {
      throw new SemanticException(
          roots.isEmpty()
              ? SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND
              : SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
          roots.isEmpty()
              ? "The lineage root was not found or is not accessible"
              : "The lineage root ID is ambiguous across object types");
    }
    String rootId = resolvedId(model, roots.getFirst());
    Graph graph = buildGraph(principal, model, request.includePhysical());
    if (!graph.nodes().containsKey(rootId)) {
      throw new SemanticException(
          SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
          "The lineage root was not found or is not accessible");
    }
    int depth = request.maxDepth();
    if (depth < 0 || depth > config.lineageMaxDepth()) {
      throw new SemanticException(
          SemanticErrorCode.QUERY_LIMIT_EXCEEDED,
          "Lineage maxDepth must be between 0 and " + config.lineageMaxDepth());
    }
    Set<NodeType> filters =
        request.types() == null ? Set.of() : Set.copyOf(request.types());
    Map<String, List<LineageEdge>> outgoing = new HashMap<>();
    Map<String, List<LineageEdge>> incoming = new HashMap<>();
    for (LineageEdge edge : graph.edges()) {
      outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
      incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
    }
    record Visit(String id, int depth) {}
    ArrayDeque<Visit> queue = new ArrayDeque<>();
    queue.add(new Visit(rootId, 0));
    Set<String> visited = new LinkedHashSet<>();
    Set<String> included = new LinkedHashSet<>();
    Set<LineageEdge> includedEdges = new LinkedHashSet<>();
    boolean truncated = false;
    while (!queue.isEmpty()) {
      Visit visit = queue.removeFirst();
      if (!visited.add(visit.id())) continue;
      LineageNode node = graph.nodes().get(visit.id());
      if (node == null) continue;
      if (visit.id().equals(rootId) || filters.isEmpty() || filters.contains(node.type())) {
        included.add(visit.id());
      }
      if (included.size() >= config.lineageMaxNodes()) {
        truncated = !queue.isEmpty();
        break;
      }
      if (visit.depth() >= depth) {
        if (!neighbors(request.direction(), visit.id(), incoming, outgoing).isEmpty()) {
          truncated = true;
        }
        continue;
      }
      for (LineageEdge edge : neighbors(request.direction(), visit.id(), incoming, outgoing)) {
        String next = edge.from().equals(visit.id()) ? edge.to() : edge.from();
        LineageNode nextNode = graph.nodes().get(next);
        if (nextNode == null) continue;
        if (filters.isEmpty() || filters.contains(nextNode.type()) || next.equals(rootId)) {
          includedEdges.add(edge);
          queue.addLast(new Visit(next, visit.depth() + 1));
        }
      }
    }
    List<LineageNode> nodes =
        included.stream().map(graph.nodes()::get).sorted(Comparator.comparing(LineageNode::id)).toList();
    List<LineageEdge> edges =
        includedEdges.stream()
            .filter(edge -> included.contains(edge.from()) && included.contains(edge.to()))
            .sorted(
                Comparator.comparing(LineageEdge::from)
                    .thenComparing(LineageEdge::to)
                    .thenComparing(LineageEdge::type))
            .toList();
    return new LineageResult(rootId, nodes, edges, truncated, model.revision());
  }

  private Graph buildGraph(
      SemanticPrincipal principal, SemanticModel model, boolean requestedPhysical) {
    Map<String, LineageNode> nodes = new LinkedHashMap<>();
    Set<LineageEdge> edges = new LinkedHashSet<>();
    for (SemanticModel.SemanticObject object : model.objects().values()) {
      if (!policy.canReadObject(principal, model, object)) continue;
      String objectId = SemanticIds.objectId(model, object);
      nodes.put(
          objectId,
          new LineageNode(
              objectId,
              NodeType.semantic_object,
              object.metadata().label(),
              "semantic"));
      for (SemanticModel.Dimension dimension : object.spec().dimensions()) {
        if (!policy.canReadDimension(principal, model, object, dimension)) continue;
        String dimensionId = SemanticIds.dimensionId(model, object, dimension);
        nodes.put(
            dimensionId,
            new LineageNode(
                dimensionId,
                NodeType.dimension,
                Objects.requireNonNullElse(dimension.label(), dimension.name()),
                "semantic"));
        edges.add(new LineageEdge(objectId, dimensionId, "HAS_DIMENSION", "semantic"));
      }
      for (SemanticModel.Relationship relationship : object.spec().relationships()) {
        SemanticModel.SemanticObject target = model.objects().get(relationship.targetObject());
        if (target == null || !policy.canReadObject(principal, model, target)) continue;
        String targetId = SemanticIds.objectId(model, target);
        nodes.putIfAbsent(
            targetId,
            new LineageNode(
                targetId,
                NodeType.semantic_object,
                target.metadata().label(),
                "semantic"));
        edges.add(new LineageEdge(objectId, targetId, "RELATES_TO", "semantic"));
      }
      if (requestedPhysical
          && policy.canViewPhysicalLineage(principal)
          && !object.spec().source().derived()) {
        String physicalId =
            "physical:"
                + object.spec().source().catalog()
                + "."
                + object.spec().source().schema()
                + "."
                + object.spec().source().table();
        nodes.put(
            physicalId,
            new LineageNode(
                physicalId,
                NodeType.physical_object,
                object.spec().source().table(),
                "physical"));
        edges.add(new LineageEdge(physicalId, objectId, "SOURCES", "physical"));
      }
    }
    for (SemanticModel.Metric metric : model.metrics().values()) {
      if (!policy.canReadMetric(principal, model, metric)) continue;
      SemanticModel.SemanticObject base = model.objects().get(metric.spec().baseObject());
      if (base == null || !policy.canReadObject(principal, model, base)) continue;
      String metricId = SemanticIds.metricId(model, metric);
      String objectId = SemanticIds.objectId(model, base);
      nodes.put(
          metricId,
          new LineageNode(
              metricId, NodeType.metric, metric.metadata().label(), "semantic"));
      edges.add(new LineageEdge(objectId, metricId, "DERIVES", "semantic"));
      Set<String> referenced = referencedDimensions(metric, base);
      for (SemanticModel.Dimension dimension : base.spec().dimensions()) {
        if (!referenced.contains(dimension.name())
            || !policy.canReadDimension(principal, model, base, dimension)) continue;
        String dimensionId = SemanticIds.dimensionId(model, base, dimension);
        if (nodes.containsKey(dimensionId)) {
          edges.add(new LineageEdge(dimensionId, metricId, "USES", "semantic"));
        }
      }
    }
    return new Graph(Map.copyOf(nodes), Set.copyOf(edges));
  }

  private Set<String> referencedDimensions(
      SemanticModel.Metric metric, SemanticModel.SemanticObject base) {
    Set<String> references = new HashSet<>();
    String expression = Objects.toString(metric.spec().expression(), "");
    for (SemanticModel.Dimension dimension : base.spec().dimensions()) {
      Pattern token =
          Pattern.compile(
              "(?i)(?<![A-Za-z0-9_])"
                  + Pattern.quote(dimension.name())
                  + "(?![A-Za-z0-9_])");
      if (token.matcher(expression).find()) references.add(dimension.name());
    }
    metric.spec().filters().forEach(filter -> references.add(filter.field()));
    return references;
  }

  private List<LineageEdge> neighbors(
      Direction direction,
      String id,
      Map<String, List<LineageEdge>> incoming,
      Map<String, List<LineageEdge>> outgoing) {
    List<LineageEdge> result = new ArrayList<>();
    if (direction == Direction.upstream || direction == Direction.both) {
      result.addAll(incoming.getOrDefault(id, List.of()));
    }
    if (direction == Direction.downstream || direction == Direction.both) {
      result.addAll(outgoing.getOrDefault(id, List.of()));
    }
    return result.stream()
        .distinct()
        .sorted(
            Comparator.comparing(LineageEdge::from)
                .thenComparing(LineageEdge::to)
                .thenComparing(LineageEdge::type))
        .toList();
  }

  private String resolvedId(SemanticModel model, SemanticIds.ResolvedObject resolved) {
    return switch (resolved.type()) {
      case "metric" -> SemanticIds.metricId(model, resolved.metric());
      case "dimension" ->
          SemanticIds.dimensionId(model, resolved.object(), resolved.dimension());
      default -> SemanticIds.objectId(model, resolved.object());
    };
  }

  private record Graph(Map<String, LineageNode> nodes, Set<LineageEdge> edges) {}

  public enum Direction {
    upstream,
    downstream,
    both
  }

  public enum NodeType {
    metric,
    dimension,
    semantic_object,
    physical_object
  }

  public record LineageRequest(
      String objectId,
      Direction direction,
      int maxDepth,
      Set<NodeType> types,
      boolean includePhysical) {}

  public record LineageNode(String id, NodeType type, String title, String layer) {}

  public record LineageEdge(String from, String to, String type, String layer) {}

  public record LineageResult(
      String root,
      List<LineageNode> nodes,
      List<LineageEdge> edges,
      boolean truncated,
      String semanticRevision) {}
}
