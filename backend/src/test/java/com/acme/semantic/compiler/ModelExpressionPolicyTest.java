package com.acme.semantic.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.semantic.TestModels;
import org.junit.jupiter.api.Test;

class ModelExpressionPolicyTest {
  private final ModelExpressionPolicy policy = new ModelExpressionPolicy();

  @Test
  void qualifiesEveryColumnInSafeScalarExpressions() {
    assertThat(
            policy.render(
                "coalesce(amount, 0) * 1.2",
                "orders",
                ModelExpressionPolicy.ExpressionKind.METRIC_VALUE))
        .isEqualTo("COALESCE(\"orders\".\"amount\", 0) * 1.2");
  }

  @Test
  void permitsAggregatesOnlyForCustomAggregateDefinitions() {
    assertThat(
            policy.render(
                "sum(amount) / nullif(count(order_id), 0)",
                "orders",
                ModelExpressionPolicy.ExpressionKind.CUSTOM_AGGREGATE))
        .isEqualTo(
            "SUM(\"orders\".\"amount\") / NULLIF(COUNT(\"orders\".\"order_id\"), 0)");

    assertThatThrownBy(
            () ->
                policy.render(
                    "sum(amount)",
                    "orders",
                    ModelExpressionPolicy.ExpressionKind.METRIC_VALUE))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("Aggregate function is not allowed");
  }

  @Test
  void rejectsCrossTableAndSubqueryExpressions() {
    assertThatThrownBy(
            () ->
                policy.render(
                    "secrets.api_key",
                    "orders",
                    ModelExpressionPolicy.ExpressionKind.METRIC_VALUE))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("Qualified column");

    assertThatThrownBy(
            () ->
                policy.render(
                    "(SELECT token FROM secrets.api_keys LIMIT 1)",
                    "orders",
                    ModelExpressionPolicy.ExpressionKind.METRIC_VALUE))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("Unsupported model expression");

    assertThatThrownBy(
            () ->
                policy.render(
                    "system.runtime.nodes()",
                    "orders",
                    ModelExpressionPolicy.ExpressionKind.METRIC_VALUE))
        .isInstanceOf(SqlCompilationException.class);
  }

  @Test
  void resolvesMetricColumnsOnlyThroughRegisteredDimensions() {
    var orders = TestModels.demo().objects().get("orders");

    assertThat(
            policy.renderMetric(
                "amount * 2",
                "o",
                ModelExpressionPolicy.ExpressionKind.METRIC_VALUE,
                orders))
        .isEqualTo("\"o\".\"amount\" * 2");
    assertThatThrownBy(
            () ->
                policy.renderMetric(
                    "undeclared_physical_column",
                    "o",
                    ModelExpressionPolicy.ExpressionKind.METRIC_VALUE,
                    orders))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("Unknown dimension");
  }
}
