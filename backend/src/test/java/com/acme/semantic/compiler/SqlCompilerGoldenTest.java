package com.acme.semantic.compiler;

import static org.assertj.core.api.Assertions.*;

import com.acme.semantic.TestModels;
import com.acme.semantic.model.SemanticModel;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlCompilerGoldenTest {
  private final AstSemanticSqlCompiler compiler = new AstSemanticSqlCompiler();

  @Test
  void compilesMetricWithoutDoubleAggregation() {
    var q =
        compiler.compile(
            "SELECT customer_id, SUM(total_revenue) FROM semantic.orders GROUP BY customer_id ORDER"
                + " BY SUM(total_revenue) DESC LIMIT 100",
            TestModels.demo());
    assertThat(q.trinoSql())
        .isEqualTo(
            "SELECT \"orders\".\"customer_id\", SUM(\"orders\".\"amount\") FILTER (WHERE"
                + " \"orders\".\"status\" IN ('paid', 'completed')) FROM"
                + " \"postgres\".\"public\".\"orders\" \"orders\" GROUP BY"
                + " \"orders\".\"customer_id\" ORDER BY SUM(\"orders\".\"amount\") FILTER (WHERE"
                + " \"orders\".\"status\" IN ('paid', 'completed')) DESC LIMIT 100");
  }

  @Test
  void compilesDeclaredJoin() {
    var q =
        compiler.compile(
            "SELECT c.country, o.total_revenue FROM semantic.orders o JOIN semantic.customers c ON"
                + " o.customer_id = c.customer_id GROUP BY c.country",
            TestModels.demo());
    assertThat(q.trinoSql())
        .contains(
            "JOIN \"postgres\".\"public\".\"customers\" \"c\" ON \"o\".\"customer_id\" ="
                + " \"c\".\"customer_id\"");
  }

  @Test
  void rejectsDmlAndPhysicalTable() {
    assertThatThrownBy(() -> compiler.compile("DELETE FROM semantic.orders", TestModels.demo()))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("SELECT");
    assertThatThrownBy(
            () -> compiler.compile("SELECT * FROM postgres.public.orders", TestModels.demo()))
        .isInstanceOf(SqlCompilationException.class);
  }

  @Test
  void expandsWildcardToDimensionsWithoutImplicitMetrics() {
    var query = compiler.compile("SELECT * FROM retail.orders LIMIT 1", TestModels.demo());

    assertThat(query.columns())
        .extracting(CompiledQuery.Column::name)
        .containsExactly("order_id", "customer_id", "product_id", "created_at", "amount", "status");
    assertThat(query.trinoSql())
        .startsWith(
            "SELECT \"orders\".\"order_id\", \"orders\".\"customer_id\","
                + " \"orders\".\"product_id\"")
        .doesNotContain("total_revenue");
  }

  @Test
  void rejectsMetricAggregationFromTheOneSideOfFanout() {
    SemanticModel model = TestModels.demo();
    var metrics = new LinkedHashMap<>(model.metrics());
    metrics.put(
        "customer_count",
        new SemanticModel.Metric(
            1,
            "metric",
            new SemanticModel.Metadata(
                "customer_count",
                "retail",
                "Customer count",
                "Number of registered customers",
                "crm-analytics",
                List.of("crm")),
            new SemanticModel.MetricSpec(
                "customers",
                SemanticModel.Aggregation.count,
                "customer_id",
                "bigint",
                "number",
                List.of()),
            "metrics/customer_count.yaml"));
    SemanticModel withCustomerMetric =
        new SemanticModel(
            model.project(), model.objects(), metrics, model.revision(), model.loadedAt());

    assertThatThrownBy(
            () ->
                compiler.compile(
                    "SELECT c.country, c.customer_count FROM retail.customers c"
                        + " JOIN retail.orders o ON c.customer_id = o.customer_id"
                        + " GROUP BY c.country",
                    withCustomerMetric))
        .isInstanceOf(SqlCompilationException.class)
        .hasMessageContaining("fan-out-safe aggregation");
  }

  @Test
  void keepsManyToOneMetricAggregationSafe() {
    assertThatCode(
            () ->
                compiler.compile(
                    "SELECT c.country, o.total_revenue FROM retail.orders o"
                        + " JOIN retail.customers c ON o.customer_id = c.customer_id"
                        + " GROUP BY c.country",
                    TestModels.demo()))
        .doesNotThrowAnyException();
  }
}
