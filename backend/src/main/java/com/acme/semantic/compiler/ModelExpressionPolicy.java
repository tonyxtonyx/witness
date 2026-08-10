package com.acme.semantic.compiler;

import com.acme.semantic.model.SemanticModel;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.expression.operators.conditional.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;

/**
 * Parses and renders SQL expressions stored in the semantic model.
 *
 * <p>Model expressions are trusted definitions, but they can still originate from local CRUD or a
 * reviewed merge request. This class deliberately supports a small scalar-expression grammar and
 * never renders an AST node that it has not explicitly inspected.
 */
public final class ModelExpressionPolicy {
  private static final Set<String> SCALAR_FUNCTIONS =
      Set.of("lower", "upper", "coalesce", "date_trunc", "substring", "abs", "round", "nullif");

  private static final Set<String> AGGREGATE_FUNCTIONS =
      Set.of("sum", "count", "min", "max", "avg");

  private static final Pattern SAFE_CAST_TYPE =
      Pattern.compile(
          "(?i)(bigint|integer|int|smallint|double|real|boolean|date|timestamp|varchar|text|"
              + "decimal\\(\\d{1,3},\\d{1,3}\\)|numeric\\(\\d{1,3},\\d{1,3}\\))");

  public String render(String sql, String alias, ExpressionKind kind) {
    if (sql == null || sql.isBlank()) {
      throw new SqlCompilationException("42601", "Model expression is required");
    }
    if (alias == null || alias.isBlank()) {
      throw new IllegalArgumentException("A base-object alias is required");
    }

    try {
      Expression parsed = CCJSqlParserUtil.parseExpression(sql);
      return render(parsed, new RenderContext(alias, kind, null));
    } catch (SqlCompilationException e) {
      throw e;
    } catch (Exception e) {
      throw new SqlCompilationException("42601", "Invalid model expression: " + e.getMessage());
    }
  }

  public String renderMetric(
      String sql,
      String alias,
      ExpressionKind kind,
      SemanticModel.SemanticObject baseObject) {
    if (sql == null || sql.isBlank()) {
      throw new SqlCompilationException("42601", "Model expression is required");
    }
    try {
      Expression parsed = CCJSqlParserUtil.parseExpression(sql);
      return render(
          parsed,
          new RenderContext(
              alias,
              kind,
              columnName ->
                  baseObject
                      .dimension(columnName)
                      .map(
                          dimension ->
                              render(
                                  dimension.sql(), alias, ExpressionKind.DIMENSION))
                      .orElseThrow(
                          () ->
                              new SqlCompilationException(
                                  "42703",
                                  "Unknown dimension in metric expression: " + columnName))));
    } catch (SqlCompilationException e) {
      throw e;
    } catch (Exception e) {
      throw new SqlCompilationException("42601", "Invalid model expression: " + e.getMessage());
    }
  }

  private String render(Expression expression, RenderContext context) {
    String alias = context.alias();
    ExpressionKind kind = context.kind();
    if (expression instanceof Column column) {
      if (column.getTable() != null
          && column.getTable().getName() != null
          && !column.getTable().getName().isBlank()) {
        reject("Qualified column references are not allowed in model expressions");
      }
      if (context.columnResolver() != null) {
        return context.columnResolver().resolve(column.getColumnName());
      }
      return quote(alias) + "." + quote(column.getColumnName());
    }

    if (expression instanceof LongValue
        || expression instanceof DoubleValue
        || expression instanceof StringValue
        || expression instanceof DateValue
        || expression instanceof TimeValue
        || expression instanceof TimestampValue
        || expression instanceof NullValue) {
      return expression.toString();
    }

    if (expression instanceof Parenthesis parenthesis) {
      return "(" + render(parenthesis.getExpression(), context) + ")";
    }

    if (expression instanceof SignedExpression signed) {
      return signed.getSign() + render(signed.getExpression(), context);
    }

    if (expression instanceof NotExpression not) {
      return "NOT (" + render(not.getExpression(), context) + ")";
    }

    if (expression instanceof CastExpression cast) {
      String type = cast.getColDataType().toString();
      if (!SAFE_CAST_TYPE.matcher(type).matches()) {
        reject("Cast type is not allowed: " + type);
      }
      return "CAST(" + render(cast.getLeftExpression(), context) + " AS " + type + ")";
    }

    if (expression instanceof Function function) {
      return renderFunction(function, context);
    }

    if (expression instanceof CaseExpression caseExpression) {
      StringBuilder out = new StringBuilder("CASE");
      if (caseExpression.getSwitchExpression() != null) {
        out.append(" ").append(render(caseExpression.getSwitchExpression(), context));
      }
      for (WhenClause clause : caseExpression.getWhenClauses()) {
        out.append(" WHEN ")
            .append(render(clause.getWhenExpression(), context))
            .append(" THEN ")
            .append(render(clause.getThenExpression(), context));
      }
      if (caseExpression.getElseExpression() != null) {
        out.append(" ELSE ").append(render(caseExpression.getElseExpression(), context));
      }
      return out.append(" END").toString();
    }

    if (expression instanceof InExpression in) {
      return render(in.getLeftExpression(), context)
          + (in.isNot() ? " NOT IN " : " IN ")
          + render(in.getRightExpression(), context);
    }

    if (expression instanceof ExpressionList<?> list) {
      return "(" + renderList(list.getExpressions(), context) + ")";
    }

    if (expression instanceof IsNullExpression isNull) {
      return render(isNull.getLeftExpression(), context)
          + (isNull.isNot() ? " IS NOT NULL" : " IS NULL");
    }

    if (expression instanceof Between between) {
      return render(between.getLeftExpression(), context)
          + (between.isNot() ? " NOT BETWEEN " : " BETWEEN ")
          + render(between.getBetweenExpressionStart(), context)
          + " AND "
          + render(between.getBetweenExpressionEnd(), context);
    }

    if (expression instanceof BinaryExpression binary) {
      return render(binary.getLeftExpression(), context)
          + " "
          + binaryOperator(binary)
          + " "
          + render(binary.getRightExpression(), context);
    }

    reject("Unsupported model expression: " + expression.getClass().getSimpleName());
    return "";
  }

  private String renderFunction(Function function, RenderContext context) {
    if (function.getMultipartName() != null && function.getMultipartName().size() > 1) {
      reject("Qualified function names are not allowed in model expressions");
    }

    String name = function.getName().toLowerCase(Locale.ROOT);
    boolean aggregate = AGGREGATE_FUNCTIONS.contains(name);
    if (!SCALAR_FUNCTIONS.contains(name) && !aggregate) {
      reject("Function is not allowed in model expressions: " + name);
    }
    if (aggregate && context.kind() != ExpressionKind.CUSTOM_AGGREGATE) {
      reject("Aggregate function is not allowed in this model expression: " + name);
    }
    if (function.isAllColumns() && !(aggregate && name.equals("count"))) {
      reject("Wildcard function arguments are not allowed");
    }
    if (function.getNamedParameters() != null
        || function.getAttribute() != null
        || function.getKeep() != null
        || function.getOrderByElements() != null
        || function.getLimit() != null) {
      reject("Advanced function clauses are not allowed in model expressions");
    }

    List<? extends Expression> arguments =
        function.getParameters() == null ? List.of() : function.getParameters().getExpressions();
    String renderedArguments = function.isAllColumns() ? "*" : renderList(arguments, context);
    return name.toUpperCase(Locale.ROOT)
        + "("
        + (function.isDistinct() ? "DISTINCT " : "")
        + renderedArguments
        + ")";
  }

  private String renderList(List<? extends Expression> expressions, RenderContext context) {
    StringJoiner out = new StringJoiner(", ");
    for (Expression expression : expressions) {
      out.add(render(expression, context));
    }
    return out.toString();
  }

  private String binaryOperator(BinaryExpression binary) {
    if (binary instanceof EqualsTo) return "=";
    if (binary instanceof NotEqualsTo) return "<>";
    if (binary instanceof GreaterThan) return ">";
    if (binary instanceof GreaterThanEquals) return ">=";
    if (binary instanceof MinorThan) return "<";
    if (binary instanceof MinorThanEquals) return "<=";
    if (binary instanceof AndExpression) return "AND";
    if (binary instanceof OrExpression) return "OR";
    if (binary instanceof Addition) return "+";
    if (binary instanceof Subtraction) return "-";
    if (binary instanceof Multiplication) return "*";
    if (binary instanceof Division) return "/";
    if (binary instanceof Modulo) return "%";
    if (binary instanceof Concat) return "||";

    reject("Operator is not allowed in model expressions: " + binary.getClass().getSimpleName());
    return "";
  }

  private String quote(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private void reject(String message) {
    throw new SqlCompilationException("0A000", message);
  }

  public enum ExpressionKind {
    DIMENSION,
    METRIC_VALUE,
    CUSTOM_AGGREGATE
  }

  @FunctionalInterface
  private interface ColumnResolver {
    String resolve(String columnName);
  }

  private record RenderContext(
      String alias, ExpressionKind kind, ColumnResolver columnResolver) {}
}
