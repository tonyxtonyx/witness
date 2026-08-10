package com.acme.semantic.model;

import static com.acme.semantic.model.SemanticModel.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ModelParser {
  private final ObjectMapper yaml =
      new ObjectMapper(new YAMLFactory())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

  public SemanticModel parse(ModelRevision revision) {
    Project project = null;
    Map<String, SemanticObject> objects = new LinkedHashMap<>();
    Map<String, Metric> metrics = new LinkedHashMap<>();
    for (var entry : revision.files().entrySet()) {
      try {
        JsonNode root = yaml.readTree(entry.getValue());
        String kind = requiredText(root, "kind");
        switch (kind) {
          case "project" -> project = yaml.treeToValue(root, Project.class);
          case "object" -> {
            SemanticObject raw = yaml.treeToValue(root, SemanticObject.class);
            SemanticObject value =
                new SemanticObject(
                    raw.version(), raw.kind(), raw.metadata(), raw.spec(), entry.getKey());
            if (objects.putIfAbsent(value.metadata().name(), value) != null)
              throw new ModelParseException(
                  entry.getKey(),
                  "metadata.name",
                  "DUPLICATE_OBJECT",
                  "Duplicate object " + value.metadata().name());
          }
          case "metric" -> {
            Metric raw = yaml.treeToValue(root, Metric.class);
            Metric value =
                new Metric(raw.version(), raw.kind(), raw.metadata(), raw.spec(), entry.getKey());
            if (metrics.putIfAbsent(value.metadata().name(), value) != null)
              throw new ModelParseException(
                  entry.getKey(),
                  "metadata.name",
                  "DUPLICATE_METRIC",
                  "Duplicate metric " + value.metadata().name());
          }
          default ->
              throw new ModelParseException(
                  entry.getKey(), "kind", "UNKNOWN_KIND", "Unknown kind: " + kind);
        }
      } catch (ModelParseException e) {
        throw e;
      } catch (Exception e) {
        throw new ModelParseException(
            entry.getKey(), "$", "INVALID_YAML", String.valueOf(e.getMessage()));
      }
    }
    if (project == null)
      throw new ModelParseException(
          "project.yaml", "$", "MISSING_PROJECT", "project.yaml is required");
    return new SemanticModel(project, objects, metrics, revision.revision(), Instant.now());
  }

  private String requiredText(JsonNode node, String field) {
    if (!node.hasNonNull(field)) throw new IllegalArgumentException("Missing field: " + field);
    return node.get(field).asText();
  }
}
