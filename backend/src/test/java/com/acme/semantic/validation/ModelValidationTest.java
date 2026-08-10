package com.acme.semantic.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.semantic.TestModels;
import com.acme.semantic.model.SemanticModel;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelValidationTest {
  @Test
  void validatesDemoModel() {
    var result = new DefaultModelValidator().validate(TestModels.demo());
    assertThat(result.valid()).as(result.errors().toString()).isTrue();
  }

  @Test
  void rejectsUnsafeMetricExpression() {
    SemanticModel model = TestModels.demo();
    var metrics = new LinkedHashMap<>(model.metrics());
    metrics.put(
        "leaked_token",
        metric("leaked_token", SemanticModel.Aggregation.custom, "(SELECT token FROM secrets)", "text"));
    SemanticModel unsafe =
        new SemanticModel(
            model.project(), model.objects(), metrics, model.revision(), model.loadedAt());

    var result = new DefaultModelValidator().validate(unsafe);

    assertThat(result.valid()).isFalse();
    assertThat(result.errors())
        .anySatisfy(
            error -> {
              assertThat(error.path()).isEqualTo("spec.expression");
              assertThat(error.code()).isEqualTo("UNSAFE_SQL_EXPRESSION");
            });
  }

  @Test
  void rejectsAggregationResultTypeMismatch() {
    SemanticModel model = TestModels.demo();
    var metrics = new LinkedHashMap<>(model.metrics());
    metrics.put(
        "bad_count",
        metric("bad_count", SemanticModel.Aggregation.count_distinct, "customer_id", "text"));
    SemanticModel invalid =
        new SemanticModel(
            model.project(), model.objects(), metrics, model.revision(), model.loadedAt());

    var result = new DefaultModelValidator().validate(invalid);

    assertThat(result.errors())
        .anyMatch(error -> error.code().equals("INCOMPATIBLE_RESULT_TYPE"));
  }

  private SemanticModel.Metric metric(
      String name, SemanticModel.Aggregation aggregation, String expression, String resultType) {
    return new SemanticModel.Metric(
        1,
        "metric",
        new SemanticModel.Metadata(
            name,
            "retail",
            name,
            "Validation test metric",
            "analytics-platform",
            List.of("test")),
        new SemanticModel.MetricSpec(
            "orders", aggregation, expression, resultType, "number", List.of()),
        "metrics/" + name + ".yaml");
  }
}
