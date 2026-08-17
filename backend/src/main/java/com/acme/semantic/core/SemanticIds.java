package com.acme.semantic.core;

import com.acme.semantic.model.SemanticModel;
import java.util.ArrayList;
import java.util.List;

/** Stable, domain-qualified identifiers used at every external discovery boundary. */
public final class SemanticIds {
  private SemanticIds() {}

  public static String objectId(
      SemanticModel model, SemanticModel.SemanticObject object) {
    return model.domain(object) + "." + object.metadata().name();
  }

  public static String metricId(SemanticModel model, SemanticModel.Metric metric) {
    return model.domain(metric) + "." + metric.metadata().name();
  }

  public static String dimensionId(
      SemanticModel model,
      SemanticModel.SemanticObject object,
      SemanticModel.Dimension dimension) {
    return objectId(model, object) + "." + dimension.name();
  }

  public static SemanticModel.SemanticObject requireObject(
      SemanticModel model,
      SemanticAccessPolicy policy,
      SemanticPrincipal principal,
      String id) {
    String[] parts = qualified(id, 2, "semantic object");
    SemanticModel.SemanticObject object = model.objects().get(parts[1]);
    if (object == null
        || !model.domain(object).equals(parts[0])
        || !policy.canReadObject(principal, model, object)) {
      notFound(id, "semantic object");
    }
    return object;
  }

  public static SemanticModel.Metric requireMetric(
      SemanticModel model,
      SemanticAccessPolicy policy,
      SemanticPrincipal principal,
      String id) {
    String[] parts = qualified(id, 2, "metric");
    SemanticModel.Metric metric = model.metrics().get(parts[1]);
    if (metric == null
        || !model.domain(metric).equals(parts[0])
        || !policy.canReadMetric(principal, model, metric)) {
      notFound(id, "metric");
    }
    return metric;
  }

  public static ResolvedDimension requireDimension(
      SemanticModel model,
      SemanticAccessPolicy policy,
      SemanticPrincipal principal,
      String id) {
    return requireDimension(model, policy, principal, id, true);
  }

  static ResolvedDimension requirePolicyDimension(
      SemanticModel model,
      SemanticAccessPolicy policy,
      SemanticPrincipal principal,
      String id) {
    return requireDimension(model, policy, principal, id, false);
  }

  private static ResolvedDimension requireDimension(
      SemanticModel model,
      SemanticAccessPolicy policy,
      SemanticPrincipal principal,
      String id,
      boolean enforceColumnAccess) {
    String[] parts = qualified(id, 3, "dimension");
    SemanticModel.SemanticObject object = model.objects().get(parts[1]);
    SemanticModel.Dimension dimension =
        object == null ? null : object.dimension(parts[2]).orElse(null);
    if (object == null
        || dimension == null
        || !model.domain(object).equals(parts[0])
        || !policy.canReadObject(principal, model, object)
        || (enforceColumnAccess
            && !policy.canReadDimension(principal, model, object, dimension))) {
      notFound(id, "dimension");
    }
    return new ResolvedDimension(object, dimension);
  }

  public static List<ResolvedObject> resolveAny(
      SemanticModel model,
      SemanticAccessPolicy policy,
      SemanticPrincipal principal,
      String id) {
    List<ResolvedObject> matches = new ArrayList<>();
    String[] parts = id == null ? new String[0] : id.split("\\.", -1);
    if (parts.length == 2) {
      SemanticModel.SemanticObject object = model.objects().get(parts[1]);
      if (object != null
          && model.domain(object).equals(parts[0])
          && policy.canReadObject(principal, model, object)) {
        matches.add(new ResolvedObject("semantic_object", object, null, null));
      }
      SemanticModel.Metric metric = model.metrics().get(parts[1]);
      if (metric != null
          && model.domain(metric).equals(parts[0])
          && policy.canReadMetric(principal, model, metric)) {
        matches.add(new ResolvedObject("metric", null, metric, null));
      }
    } else if (parts.length == 3) {
      SemanticModel.SemanticObject object = model.objects().get(parts[1]);
      SemanticModel.Dimension dimension =
          object == null ? null : object.dimension(parts[2]).orElse(null);
      if (object != null
          && dimension != null
          && model.domain(object).equals(parts[0])
          && policy.canReadDimension(principal, model, object, dimension)) {
        matches.add(new ResolvedObject("dimension", object, null, dimension));
      }
    }
    return List.copyOf(matches);
  }

  public static boolean certified(SemanticModel.Metadata metadata) {
    return metadata != null
        && metadata.owner() != null
        && !metadata.owner().isBlank()
        && metadata.description() != null
        && !metadata.description().isBlank();
  }

  private static String[] qualified(String id, int parts, String kind) {
    if (id == null) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS, kind + " ID is required");
    }
    String normalized = id.trim();
    String[] split = normalized.split("\\.", -1);
    if (split.length != parts) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
          "Expected a fully qualified " + kind + " ID with " + parts + " segments: " + id);
    }
    for (String value : split) {
      if (value.isBlank()) {
        throw new SemanticException(
            SemanticErrorCode.INVALID_TOOL_ARGUMENTS, "Invalid " + kind + " ID: " + id);
      }
    }
    return split;
  }

  private static void notFound(String id, String kind) {
    throw new SemanticException(
        SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
        "The requested " + kind + " was not found or is not accessible: " + id,
        false,
        java.util.Map.of("id", id),
        List.of("Use search_semantic_objects to find accessible semantic IDs"));
  }

  public record ResolvedDimension(
      SemanticModel.SemanticObject object, SemanticModel.Dimension dimension) {}

  public record ResolvedObject(
      String type,
      SemanticModel.SemanticObject object,
      SemanticModel.Metric metric,
      SemanticModel.Dimension dimension) {}
}
