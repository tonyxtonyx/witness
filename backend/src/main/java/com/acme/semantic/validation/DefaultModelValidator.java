package com.acme.semantic.validation;

import static com.acme.semantic.validation.ValidationError.Severity.ERROR;

import com.acme.semantic.compiler.ModelExpressionPolicy;
import com.acme.semantic.compiler.SqlCompilationException;
import com.acme.semantic.compiler.SourceSelectPolicy;
import com.acme.semantic.model.SemanticModel;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DefaultModelValidator implements ModelValidator {
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern SEMANTIC_TYPE =
      Pattern.compile(
          "(?i)(bigint|integer|int|smallint|double|real|boolean|date|timestamp|varchar|text|"
              + "decimal\\(\\d{1,3},\\d{1,3}\\)|numeric\\(\\d{1,3},\\d{1,3}\\))");
  private static final Set<String> NUMERIC_TYPES =
      Set.of("bigint", "integer", "int", "smallint", "double", "real", "decimal", "numeric");
  private static final Set<String> FORMATS =
      Set.of("number", "decimal", "currency", "percent", "duration", "text", "date", "datetime");
  private static final ModelExpressionPolicy MODEL_EXPRESSIONS = new ModelExpressionPolicy();
  private static final SourceSelectPolicy SOURCE_SELECTS = new SourceSelectPolicy();

  @Override
  public ValidationResult validate(SemanticModel model) {
    List<ValidationError> out = new ArrayList<>();
    if (model.project().version() != 1)
      error(
          out,
          "project.yaml",
          "version",
          "UNSUPPORTED_VERSION",
          "Only schema version 1 is supported");
    if (model.project().spec() == null || blank(model.project().spec().semanticSchema()))
      error(out, "project.yaml", "spec.semanticSchema", "REQUIRED", "semanticSchema is required");

    for (var object : model.objects().values()) {
      String file = object.file();
      if (object.version() != 1)
        error(out, file, "version", "UNSUPPORTED_VERSION", "Only schema version 1 is supported");
      if (object.metadata() == null || blank(object.metadata().name())) {
        error(out, file, "metadata.name", "REQUIRED", "Object name is required");
        continue;
      }
      if (!SAFE_IDENTIFIER.matcher(object.metadata().name()).matches())
        error(
            out,
            file,
            "metadata.name",
            "INVALID_IDENTIFIER",
            "Object name is not a safe SQL identifier");
      validateGovernanceMetadata(out, file, object.metadata());
      if (!SAFE_IDENTIFIER.matcher(model.domain(object)).matches())
        error(
            out,
            file,
            "metadata.domain",
            "INVALID_IDENTIFIER",
            "Domain must be a safe PostgreSQL schema identifier");
      if (object.spec() == null || object.spec().source() == null) {
        error(out, file, "spec.source", "REQUIRED", "Object source is required");
        continue;
      }
      validateSource(out, file, object.spec().source());
      Set<String> dimensions = new HashSet<>();
      for (int i = 0; i < object.spec().dimensions().size(); i++) {
        var d = object.spec().dimensions().get(i);
        String p = "spec.dimensions[" + i + "]";
        if (blank(d.name()) || !dimensions.add(d.name()))
          error(
              out,
              file,
              p + ".name",
              "DUPLICATE_DIMENSION",
              "Dimension names must be non-empty and unique");
        if (!blank(d.name()) && !SAFE_IDENTIFIER.matcher(d.name()).matches())
          error(out, file, p + ".name", "INVALID_IDENTIFIER", "Invalid dimension identifier");
        validateSemanticType(out, file, p + ".type", d.type());
        validateExpression(
            out,
            file,
            p + ".sql",
            d.sql(),
            ModelExpressionPolicy.ExpressionKind.DIMENSION);
      }
      for (String pk : object.spec().primaryKey())
        if (!dimensions.contains(pk))
          error(
              out,
              file,
              "spec.primaryKey",
              "UNKNOWN_FIELD",
              "Primary key field does not exist: " + pk);
      Set<String> relationshipNames = new HashSet<>();
      for (int i = 0; i < object.spec().relationships().size(); i++) {
        var rel = object.spec().relationships().get(i);
        String p = "spec.relationships[" + i + "]";
        if (blank(rel.name()) || !SAFE_IDENTIFIER.matcher(rel.name()).matches()) {
          error(
              out,
              file,
              p + ".name",
              "INVALID_IDENTIFIER",
              "Relationship name must be a safe non-empty identifier");
        } else if (!relationshipNames.add(rel.name().toLowerCase(Locale.ROOT))) {
          error(
              out,
              file,
              p + ".name",
              "DUPLICATE_RELATIONSHIP",
              "Relationship names must be unique within an object");
        }
        var target = model.objects().get(rel.targetObject());
        if (target == null) {
          error(
              out,
              file,
              p + ".targetObject",
              "UNKNOWN_TARGET",
              "Target object does not exist: " + rel.targetObject());
          continue;
        }
        if (rel.sourceFields().size() != rel.targetFields().size() || rel.sourceFields().isEmpty())
          error(
              out,
              file,
              p,
              "RELATIONSHIP_ARITY",
              "sourceFields and targetFields must have equal non-zero length");
        for (int j = 0; j < Math.min(rel.sourceFields().size(), rel.targetFields().size()); j++) {
          var sourceDim = object.dimension(rel.sourceFields().get(j));
          var targetDim = target.dimension(rel.targetFields().get(j));
          if (sourceDim.isEmpty())
            error(
                out, file, p + ".sourceFields[" + j + "]", "UNKNOWN_FIELD", "Unknown source field");
          if (targetDim.isEmpty())
            error(
                out, file, p + ".targetFields[" + j + "]", "UNKNOWN_FIELD", "Unknown target field");
          if (sourceDim.isPresent()
              && targetDim.isPresent()
              && !normalizeType(sourceDim.get().type())
                  .equals(normalizeType(targetDim.get().type())))
            error(
                out, file, p, "INCOMPATIBLE_TYPES", "Relationship fields have incompatible types");
        }
        validateRelationshipGrain(out, file, p, object, target, rel);
      }
    }
    for (var metric : model.metrics().values()) {
      String file = metric.file();
      if (metric.metadata() == null || blank(metric.metadata().name())) {
        error(out, file, "metadata.name", "REQUIRED", "Metric name is required");
        continue;
      }
      if (!SAFE_IDENTIFIER.matcher(metric.metadata().name()).matches())
        error(out, file, "metadata.name", "INVALID_IDENTIFIER", "Invalid metric identifier");
      validateGovernanceMetadata(out, file, metric.metadata());
      if (metric.spec() == null) {
        error(out, file, "spec", "REQUIRED", "Metric specification is required");
        continue;
      }
      var base = model.objects().get(metric.spec().baseObject());
      if (metric.version() != 1)
        error(out, file, "version", "UNSUPPORTED_VERSION", "Only schema version 1 is supported");
      if (base == null) {
        error(
            out,
            file,
            "spec.baseObject",
            "UNKNOWN_BASE_OBJECT",
            "Base object does not exist: " + metric.spec().baseObject());
        continue;
      }
      if (!SAFE_IDENTIFIER.matcher(model.domain(metric)).matches())
        error(
            out,
            file,
            "metadata.domain",
            "INVALID_IDENTIFIER",
            "Domain must be a safe PostgreSQL schema identifier");
      if (!model.domain(metric).equals(model.domain(base)))
        error(
            out,
            file,
            "metadata.domain",
            "DOMAIN_MISMATCH",
            "Metric domain must match its base object's domain");
      validateMetricExpression(
          out,
          file,
          "spec.expression",
          metric.spec().expression(),
          metric.spec().aggregation() == SemanticModel.Aggregation.custom
              ? ModelExpressionPolicy.ExpressionKind.CUSTOM_AGGREGATE
              : ModelExpressionPolicy.ExpressionKind.METRIC_VALUE,
          base);
      validateSemanticType(out, file, "spec.resultType", metric.spec().resultType());
      validateMetricType(out, file, metric);
      if (blank(metric.spec().format())
          || !FORMATS.contains(metric.spec().format().toLowerCase(Locale.ROOT)))
        error(
            out,
            file,
            "spec.format",
            "INVALID_FORMAT",
            "Format must be one of: " + String.join(", ", FORMATS));
      for (int i = 0; i < metric.spec().filters().size(); i++) {
        var filter = metric.spec().filters().get(i);
        if (base.dimension(filter.field()).isEmpty())
          error(
              out,
              file,
              "spec.filters[" + i + "].field",
              "UNKNOWN_FIELD",
              "Metric filter field does not exist");
        int valueCount = filter.values().size();
        boolean nullOperator =
            filter.operator() == SemanticModel.FilterOperator.is_null
                || filter.operator() == SemanticModel.FilterOperator.is_not_null;
        boolean setOperator =
            filter.operator() == SemanticModel.FilterOperator.in
                || filter.operator() == SemanticModel.FilterOperator.not_in;
        if ((nullOperator && valueCount != 0)
            || (setOperator && valueCount == 0)
            || (!nullOperator && !setOperator && valueCount != 1))
          error(
              out,
              file,
              "spec.filters[" + i + "].values",
              "INVALID_FILTER_ARITY",
              "Filter value count does not match operator " + filter.operator());
      }
      if (base.dimension(metric.metadata().name()).isPresent())
        error(
            out,
            file,
            "metadata.name",
            "NAME_COLLISION",
            "Metric collides with dimension on base object");
      SemanticModel.SemanticObject sameNameObject =
          model.objects().get(metric.metadata().name());
      if (sameNameObject != null && model.domain(sameNameObject).equals(model.domain(metric))) {
        error(
            out,
            file,
            "metadata.name",
            "GLOBAL_ID_COLLISION",
            "Metric and semantic object cannot share the same domain-qualified ID");
      }
    }
    return ValidationResult.of(out);
  }

  private void validateExpression(
      List<ValidationError> out,
      String file,
      String path,
      String expression,
      ModelExpressionPolicy.ExpressionKind kind) {
    if (blank(expression)) {
      error(out, file, path, "REQUIRED", "SQL expression is required");
      return;
    }
    try {
      MODEL_EXPRESSIONS.render(expression, "base", kind);
    } catch (SqlCompilationException e) {
      error(out, file, path, "UNSAFE_SQL_EXPRESSION", e.getMessage());
    }
  }

  private void validateSource(
      List<ValidationError> out, String file, SemanticModel.Source source) {
    boolean derived = !blank(source.select());
    boolean hasTableFields =
        !blank(source.catalog()) || !blank(source.schema()) || !blank(source.table());
    if (derived && hasTableFields) {
      error(
          out,
          file,
          "spec.source",
          "AMBIGUOUS_SOURCE",
          "Use either catalog/schema/table or select, not both");
      return;
    }
    if (derived) {
      try {
        SOURCE_SELECTS.render(source.select());
      } catch (SqlCompilationException e) {
        error(out, file, "spec.source.select", "UNSAFE_SOURCE_SELECT", e.getMessage());
      }
      return;
    }
    if (blank(source.catalog()) || blank(source.schema()) || blank(source.table())) {
      error(
          out,
          file,
          "spec.source",
          "REQUIRED",
          "Table source requires catalog, schema, and table");
      return;
    }
    validatePhysicalIdentifier(out, file, "spec.source.catalog", source.catalog());
    validatePhysicalIdentifier(out, file, "spec.source.schema", source.schema());
    validatePhysicalIdentifier(out, file, "spec.source.table", source.table());
  }

  private void validatePhysicalIdentifier(
      List<ValidationError> out, String file, String path, String value) {
    if (!SAFE_IDENTIFIER.matcher(value).matches()) {
      error(out, file, path, "INVALID_IDENTIFIER", "Invalid physical source identifier");
    }
  }

  private void validateMetricExpression(
      List<ValidationError> out,
      String file,
      String path,
      String expression,
      ModelExpressionPolicy.ExpressionKind kind,
      SemanticModel.SemanticObject baseObject) {
    if (blank(expression)) {
      error(out, file, path, "REQUIRED", "SQL expression is required");
      return;
    }
    try {
      MODEL_EXPRESSIONS.renderMetric(expression, "base", kind, baseObject);
    } catch (SqlCompilationException e) {
      error(out, file, path, "UNSAFE_SQL_EXPRESSION", e.getMessage());
    }
  }

  private void validateGovernanceMetadata(
      List<ValidationError> out, String file, SemanticModel.Metadata metadata) {
    if (blank(metadata.label()))
      error(out, file, "metadata.label", "REQUIRED", "Label is required");
    if (blank(metadata.description()))
      error(out, file, "metadata.description", "REQUIRED", "Description is required");
    if (blank(metadata.owner()))
      error(out, file, "metadata.owner", "REQUIRED", "Owner is required");
    Set<String> aliases = new HashSet<>();
    for (int i = 0; i < metadata.aliases().size(); i++) {
      String alias = metadata.aliases().get(i);
      if (blank(alias) || !aliases.add(alias.toLowerCase(Locale.ROOT))) {
        error(
            out,
            file,
            "metadata.aliases[" + i + "]",
            "INVALID_ALIAS",
            "Aliases must be non-empty and unique ignoring case");
      }
    }
  }

  private void validateSemanticType(
      List<ValidationError> out, String file, String path, String value) {
    if (blank(value) || !SEMANTIC_TYPE.matcher(value).matches())
      error(out, file, path, "INVALID_SEMANTIC_TYPE", "Unsupported semantic type: " + value);
  }

  private void validateMetricType(
      List<ValidationError> out, String file, SemanticModel.Metric metric) {
    String type = normalizeType(metric.spec().resultType());
    switch (metric.spec().aggregation()) {
      case count, count_distinct -> {
        if (!Set.of("bigint", "integer", "int").contains(type))
          error(
              out,
              file,
              "spec.resultType",
              "INCOMPATIBLE_RESULT_TYPE",
              metric.spec().aggregation() + " metrics must use an integer result type");
      }
      case sum, avg -> {
        if (!NUMERIC_TYPES.contains(type))
          error(
              out,
              file,
              "spec.resultType",
              "INCOMPATIBLE_RESULT_TYPE",
              metric.spec().aggregation() + " metrics must use a numeric result type");
      }
      default -> {
        // min, max and custom may intentionally return any controlled semantic type.
      }
    }
  }

  private void validateRelationshipGrain(
      List<ValidationError> out,
      String file,
      String path,
      SemanticModel.SemanticObject source,
      SemanticModel.SemanticObject target,
      SemanticModel.Relationship relationship) {
    if (relationship.cardinality() == null) {
      error(out, file, path + ".cardinality", "REQUIRED", "Relationship cardinality is required");
      return;
    }
    boolean sourceUnique = sameFields(relationship.sourceFields(), source.spec().primaryKey());
    boolean targetUnique = sameFields(relationship.targetFields(), target.spec().primaryKey());
    boolean consistent =
        switch (relationship.cardinality()) {
          case one_to_one -> sourceUnique && targetUnique;
          case many_to_one -> targetUnique;
          case one_to_many -> sourceUnique;
          case many_to_many -> true;
        };
    if (!consistent)
      error(
          out,
          file,
          path + ".cardinality",
          "UNPROVEN_CARDINALITY",
          "Declared cardinality is not supported by the referenced primary keys");
  }

  private boolean sameFields(List<String> fields, List<String> primaryKey) {
    return !primaryKey.isEmpty()
        && fields.size() == primaryKey.size()
        && new HashSet<>(fields).equals(new HashSet<>(primaryKey));
  }

  private String normalizeType(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "");
  }

  private boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private void error(
      List<ValidationError> out, String file, String path, String code, String message) {
    out.add(new ValidationError(file, path, code, message, ERROR));
  }
}
