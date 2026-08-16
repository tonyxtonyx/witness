package com.acme.semantic.compiler;

import com.acme.semantic.model.SemanticModel;
import java.util.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.expression.operators.conditional.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.stereotype.Component;

@Component
public class AstSemanticSqlCompiler implements SemanticSqlCompiler {
  private static final int MAX_SQL = 64 * 1024;
  private static final ModelExpressionPolicy MODEL_EXPRESSIONS = new ModelExpressionPolicy();
  private static final SourceSelectPolicy SOURCE_SELECTS = new SourceSelectPolicy();
  private static final Set<String> AGGREGATE_FUNCTIONS =
      Set.of("sum", "count", "min", "max", "avg");
  private static final Set<String> FUNCTIONS =
      Set.of(
          "lower",
          "upper",
          "coalesce",
          "date_trunc",
          "cast",
          "substring",
          "abs",
          "round",
          "sum",
          "count",
          "min",
          "max",
          "avg");

  @Override
  public CompiledQuery compile(String sql, SemanticModel model) {
    if (sql == null || sql.isBlank() || sql.length() > MAX_SQL)
      fail("42601", "SQL must be non-empty and at most 64 KiB");
    if (sql.contains("--") || sql.contains("/*")) fail("42601", "SQL comments are not supported");
    Statement statement;
    try {
      statement = CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      throw new SqlCompilationException("42601", "Invalid SQL: " + e.getMessage());
    }
    if (!(statement instanceof Select)) fail("0A000", "Only read-only SELECT is supported");
    Select select = (Select) statement;
    PlainSelect plain;
    try {
      plain = select.getPlainSelect();
    } catch (Exception e) {
      throw new SqlCompilationException(
          "0A000", "Set operations and nested SELECT are not supported");
    }
    Context context = new Context(model);
    Table baseTable = requireTable(plain.getFromItem());
    context.add(baseTable);
    if (plain.getJoins() != null)
      for (Join join : plain.getJoins()) context.add(requireTable(join.getRightItem()));
    StringBuilder out = new StringBuilder("SELECT ");
    List<CompiledQuery.Column> columns = new ArrayList<>();
    boolean firstProjection = true;
    for (int i = 0; i < plain.getSelectItems().size(); i++) {
      SelectItem<?> item = plain.getSelectItems().get(i);
      if (item.getExpression() instanceof AllColumns allColumns) {
        if (item.getAlias() != null
            || allColumns.getExceptColumns() != null
            || (allColumns.getReplaceExpressions() != null
                && !allColumns.getReplaceExpressions().isEmpty())) {
          fail("0A000", "Wildcard aliases, EXCEPT, and REPLACE are not supported");
        }
        String expansionAlias;
        if (allColumns instanceof AllTableColumns tableColumns) {
          expansionAlias = unquote(tableColumns.getTable().getName());
        } else {
          if (context.objects.size() != 1)
            fail("42702", "Unqualified * is ambiguous when multiple objects are joined");
          expansionAlias = context.baseAlias;
        }
        SemanticModel.SemanticObject object = context.objects.get(expansionAlias);
        if (object == null) fail("42P01", "Unknown semantic object alias: " + expansionAlias);
        for (var dimension : object.spec().dimensions()) {
          if (!firstProjection) out.append(", ");
          firstProjection = false;
          out.append(
              expressionSql(
                  dimension.sql(),
                  expansionAlias,
                  ModelExpressionPolicy.ExpressionKind.DIMENSION));
          columns.add(new CompiledQuery.Column(dimension.name(), dimension.type()));
        }
        continue;
      }
      if (!firstProjection) out.append(", ");
      firstProjection = false;
      String expression = expression(item.getExpression(), context, Usage.SELECT);
      out.append(expression);
      String alias =
          item.getAlias() == null
              ? outputName(item.getExpression())
              : unquote(item.getAlias().getName());
      if (item.getAlias() != null) out.append(" AS ").append(q(alias));
      columns.add(new CompiledQuery.Column(alias, typeOf(item.getExpression(), context)));
    }
    out.append(" FROM ").append(physical(baseTable, context));
    if (plain.getJoins() != null)
      for (Join join : plain.getJoins()) {
        Table target = requireTable(join.getRightItem());
        if (join.getOnExpressions() == null || join.getOnExpressions().isEmpty())
          fail("42601", "JOIN requires ON");
        validateRelationship(baseTable, target, join.getOnExpressions(), context);
        out.append(
                join.isLeft()
                    ? " LEFT JOIN "
                    : join.isRight() ? " RIGHT JOIN " : join.isFull() ? " FULL JOIN " : " JOIN ")
            .append(physical(target, context))
            .append(" ON ");
        int onIndex = 0;
        for (Expression on : join.getOnExpressions()) {
          if (onIndex++ > 0) out.append(" AND ");
          out.append(expression(on, context, Usage.JOIN));
        }
      }
    if (plain.getWhere() != null)
      out.append(" WHERE ").append(expression(plain.getWhere(), context, Usage.FILTER));
    GroupByElement group = plain.getGroupBy();
    if (group != null && group.getGroupByExpressionList() != null) {
      out.append(" GROUP BY ");
      List<?> expressions = group.getGroupByExpressionList().getExpressions();
      for (int i = 0; i < expressions.size(); i++) {
        if (i > 0) out.append(", ");
        out.append(expression((Expression) expressions.get(i), context, Usage.GROUP));
      }
    }
    if (plain.getHaving() != null)
      out.append(" HAVING ").append(expression(plain.getHaving(), context, Usage.HAVING));
    if (plain.getOrderByElements() != null) {
      out.append(" ORDER BY ");
      for (int i = 0; i < plain.getOrderByElements().size(); i++) {
        if (i > 0) out.append(", ");
        var order = plain.getOrderByElements().get(i);
        out.append(expression(order.getExpression(), context, Usage.ORDER));
        if (!order.isAsc()) out.append(" DESC");
      }
    }
    long requested =
        plain.getLimit() != null && plain.getLimit().getRowCount() instanceof LongValue l
            ? l.getValue()
            : 10_000;
    out.append(" LIMIT ").append(Math.min(requested, 10_000));
    validateFanout(context);
    return new CompiledQuery(
        out.toString(), context.parameters, columns, UUID.randomUUID().toString());
  }

  private String expression(Expression e, Context c, Usage usage) {
    if (e instanceof Column col) return column(col, c, usage);
    if (e instanceof Function f) return function(f, c, usage);
    if (e instanceof LongValue
        || e instanceof DoubleValue
        || e instanceof StringValue
        || e instanceof DateValue
        || e instanceof TimeValue
        || e instanceof TimestampValue
        || e instanceof NullValue) return e.toString();
    if (e instanceof JdbcParameter p) {
      c.parameters.add(new CompiledQuery.Parameter(c.parameters.size() + 1));
      return "?";
    }
    if (e instanceof Parenthesis parenthesis)
      return "(" + expression(parenthesis.getExpression(), c, usage) + ")";
    if (e instanceof SignedExpression s)
      return s.getSign() + expression(s.getExpression(), c, usage);
    if (e instanceof CastExpression cast)
      return "CAST("
          + expression(cast.getLeftExpression(), c, usage)
          + " AS "
          + cast.getColDataType()
          + ")";
    if (e instanceof InExpression in)
      return expression(in.getLeftExpression(), c, usage)
          + (in.isNot() ? " NOT IN " : " IN ")
          + expression(in.getRightExpression(), c, usage);
    if (e instanceof ExpressionList<?> list)
      return "(" + joinExpressions(list.getExpressions(), c, usage) + ")";
    if (e instanceof IsNullExpression n)
      return expression(n.getLeftExpression(), c, usage)
          + (n.isNot() ? " IS NOT NULL" : " IS NULL");
    if (e instanceof Between b)
      return expression(b.getLeftExpression(), c, usage)
          + (b.isNot() ? " NOT BETWEEN " : " BETWEEN ")
          + expression(b.getBetweenExpressionStart(), c, usage)
          + " AND "
          + expression(b.getBetweenExpressionEnd(), c, usage);
    if (e instanceof BinaryExpression b)
      return expression(b.getLeftExpression(), c, usage)
          + " "
          + binaryOperator(b)
          + " "
          + expression(b.getRightExpression(), c, usage);
    fail("0A000", "Unsupported SQL expression: " + e.getClass().getSimpleName());
    return "";
  }

  private String function(Function f, Context c, Usage usage) {
    String name = f.getName().toLowerCase(Locale.ROOT);
    if (!FUNCTIONS.contains(name)) fail("0A000", "Function is not allowed: " + name);
    List<? extends Expression> args =
        f.getParameters() == null ? List.of() : f.getParameters().getExpressions();
    if (args.size() == 1 && args.getFirst() instanceof Column col) {
      Resolved resolved = c.resolve(col);
      if (resolved.metric != null && AGGREGATE_FUNCTIONS.contains(name))
        return metric(resolved.metric, resolved.alias, c);
    }
    if (AGGREGATE_FUNCTIONS.contains(name)) {
      if (f.isAllColumns()) c.measureAliases.add(c.baseAlias);
      else for (Expression argument : args) collectMeasureAliases(argument, c);
    }
    if (name.equals("count") && f.isAllColumns()) return "COUNT(*)";
    return name.toUpperCase(Locale.ROOT)
        + "("
        + (f.isDistinct() ? "DISTINCT " : "")
        + joinExpressions(args, c, usage)
        + ")";
  }

  private String column(Column col, Context c, Usage usage) {
    Resolved r = c.resolve(col);
    if (r.metric != null) {
      if (usage == Usage.GROUP || usage == Usage.JOIN || usage == Usage.FILTER)
        fail("42803", "Metric cannot be used in " + usage + ": " + r.metric.metadata().name());
      return metric(r.metric, r.alias, c);
    }
    return expressionSql(
        r.dimension.sql(), r.alias, ModelExpressionPolicy.ExpressionKind.DIMENSION);
  }

  private String metric(SemanticModel.Metric metric, String alias, Context c) {
    c.measureAliases.add(alias);
    var spec = metric.spec();
    SemanticModel.SemanticObject baseObject = c.objects.get(alias);
    String value =
        spec.aggregation() == SemanticModel.Aggregation.custom
            ? null
            : MODEL_EXPRESSIONS.renderMetric(
                spec.expression(),
                alias,
                ModelExpressionPolicy.ExpressionKind.METRIC_VALUE,
                baseObject);
    String aggregate =
        switch (spec.aggregation()) {
          case sum -> "SUM(" + value + ")";
          case count -> "COUNT(" + value + ")";
          case count_distinct -> "COUNT(DISTINCT " + value + ")";
          case min -> "MIN(" + value + ")";
          case max -> "MAX(" + value + ")";
          case avg -> "AVG(" + value + ")";
          case custom ->
              MODEL_EXPRESSIONS.renderMetric(
                  spec.expression(),
                  alias,
                  ModelExpressionPolicy.ExpressionKind.CUSTOM_AGGREGATE,
                  baseObject);
        };
    if (spec.filters().isEmpty()) return aggregate;
    List<String> filters = new ArrayList<>();
    for (var f : spec.filters()) {
      String field =
          expressionSql(
              c.objects.get(alias).dimension(f.field()).orElseThrow().sql(),
              alias,
              ModelExpressionPolicy.ExpressionKind.DIMENSION);
      String values =
          f.values().stream().map(this::literal).reduce((a, b) -> a + ", " + b).orElse("");
      filters.add(
          switch (f.operator()) {
            case in -> field + " IN (" + values + ")";
            case not_in -> field + " NOT IN (" + values + ")";
            case eq -> field + " = " + literal(f.values().getFirst());
            case neq -> field + " <> " + literal(f.values().getFirst());
            case gt -> field + " > " + literal(f.values().getFirst());
            case gte -> field + " >= " + literal(f.values().getFirst());
            case lt -> field + " < " + literal(f.values().getFirst());
            case lte -> field + " <= " + literal(f.values().getFirst());
            case is_null -> field + " IS NULL";
            case is_not_null -> field + " IS NOT NULL";
          });
    }
    return aggregate + " FILTER (WHERE " + String.join(" AND ", filters) + ")";
  }

  private void validateRelationship(
      Table leftTable, Table rightTable, Collection<Expression> ons, Context c) {
    String leftAlias = alias(leftTable), rightAlias = alias(rightTable);
    var left = c.objects.get(leftAlias);
    var right = c.objects.get(rightAlias);
    Optional<SemanticModel.Relationship> fromLeft =
        left.spec().relationships().stream()
            .filter(
                r ->
                    r.targetObject().equals(right.metadata().name())
                        && onMatches(r.sourceFields(), r.targetFields(), leftAlias, rightAlias, ons))
            .findFirst();
    Optional<SemanticModel.Relationship> fromRight =
        right.spec().relationships().stream()
            .filter(
                r ->
                    r.targetObject().equals(left.metadata().name())
                        && onMatches(r.sourceFields(), r.targetFields(), rightAlias, leftAlias, ons))
            .findFirst();
    if (fromLeft.isEmpty() && fromRight.isEmpty()) {
      fail(
          "42809",
          "JOIN does not match a declared relationship between "
              + left.metadata().name()
              + " and "
              + right.metadata().name());
    }
    if (fromLeft.isPresent()) {
      c.relationships.add(
          new JoinEdge(leftAlias, rightAlias, fromLeft.get().name(), fromLeft.get().cardinality()));
    } else {
      c.relationships.add(
          new JoinEdge(rightAlias, leftAlias, fromRight.get().name(), fromRight.get().cardinality()));
    }
  }

  private boolean onMatches(
      List<String> source, List<String> target, String sa, String ta, Collection<Expression> ons) {
    Set<String> actual = new HashSet<>();
    for (Expression e : ons) collectEqualities(e, actual);
    for (int i = 0; i < source.size(); i++)
      if (!actual.contains(sa + "." + source.get(i) + "=" + ta + "." + target.get(i))
          && !actual.contains(ta + "." + target.get(i) + "=" + sa + "." + source.get(i)))
        return false;
    return true;
  }

  private void collectEqualities(Expression e, Set<String> out) {
    if (e instanceof AndExpression a) {
      collectEqualities(a.getLeftExpression(), out);
      collectEqualities(a.getRightExpression(), out);
    } else if (e instanceof EqualsTo eq
        && eq.getLeftExpression() instanceof Column l
        && eq.getRightExpression() instanceof Column r) out.add(columnKey(l) + "=" + columnKey(r));
  }

  private String columnKey(Column c) {
    return unquote(c.getTable().getName()) + "." + unquote(c.getColumnName());
  }

  private String physical(Table table, Context c) {
    String a = alias(table);
    var o = c.objects.get(a);
    if (o.spec().source().derived()) {
      return "(" + SOURCE_SELECTS.render(o.spec().source().select()) + ") " + q(a);
    }
    return q(o.spec().source().catalog())
        + "."
        + q(o.spec().source().schema())
        + "."
        + q(o.spec().source().table())
        + " "
        + q(a);
  }

  private String expressionSql(
      String sql, String alias, ModelExpressionPolicy.ExpressionKind expressionKind) {
    return MODEL_EXPRESSIONS.render(sql, alias, expressionKind);
  }

  private void collectMeasureAliases(Expression expression, Context context) {
    if (expression instanceof Column column) {
      context.measureAliases.add(context.resolve(column).alias);
    } else if (expression instanceof Function function && function.getParameters() != null) {
      function.getParameters().getExpressions().forEach(e -> collectMeasureAliases(e, context));
    } else if (expression instanceof BinaryExpression binary) {
      collectMeasureAliases(binary.getLeftExpression(), context);
      collectMeasureAliases(binary.getRightExpression(), context);
    } else if (expression instanceof Parenthesis parenthesis) {
      collectMeasureAliases(parenthesis.getExpression(), context);
    } else if (expression instanceof SignedExpression signed) {
      collectMeasureAliases(signed.getExpression(), context);
    } else if (expression instanceof CastExpression cast) {
      collectMeasureAliases(cast.getLeftExpression(), context);
    } else if (expression instanceof ExpressionList<?> list) {
      list.getExpressions().forEach(e -> collectMeasureAliases(e, context));
    }
  }

  private void validateFanout(Context context) {
    for (String measureAlias : context.measureAliases) {
      for (JoinEdge relationship : context.relationships) {
        boolean unsafe =
            switch (relationship.cardinality()) {
              case one_to_one -> false;
              case many_to_one -> measureAlias.equals(relationship.targetAlias());
              case one_to_many -> measureAlias.equals(relationship.sourceAlias());
              case many_to_many ->
                  measureAlias.equals(relationship.sourceAlias())
                      || measureAlias.equals(relationship.targetAlias());
            };
        if (unsafe) {
          fail(
              "0A000",
              "Aggregate from "
                  + measureAlias
                  + " may be duplicated by relationship "
                  + relationship.name()
                  + " ("
                  + relationship.cardinality()
                  + "); fan-out-safe aggregation is not yet supported");
        }
      }
    }
  }

  private String joinExpressions(List<? extends Expression> list, Context c, Usage usage) {
    StringJoiner j = new StringJoiner(", ");
    for (Expression e : list) j.add(expression(e, c, usage));
    return j.toString();
  }

  private String binaryOperator(BinaryExpression b) {
    if (b instanceof EqualsTo) return "=";
    if (b instanceof NotEqualsTo) return "<>";
    if (b instanceof GreaterThan) return ">";
    if (b instanceof GreaterThanEquals) return ">=";
    if (b instanceof MinorThan) return "<";
    if (b instanceof MinorThanEquals) return "<=";
    if (b instanceof AndExpression) return "AND";
    if (b instanceof OrExpression) return "OR";
    if (b instanceof Addition) return "+";
    if (b instanceof Subtraction) return "-";
    if (b instanceof Multiplication) return "*";
    if (b instanceof Division) return "/";
    fail("0A000", "Unsupported operator");
    return "";
  }

  private String typeOf(Expression e, Context c) {
    if (e instanceof Column col) {
      Resolved r = c.resolve(col);
      return r.metric != null ? r.metric.spec().resultType() : r.dimension.type();
    }
    return "varchar";
  }

  private String outputName(Expression e) {
    if (e instanceof Column c) return unquote(c.getColumnName());
    if (e instanceof Function f) return f.getName().toLowerCase(Locale.ROOT);
    return "expression";
  }

  private String literal(Object v) {
    if (v == null) return "NULL";
    if (v instanceof Number || v instanceof Boolean) return v.toString();
    return "'" + v.toString().replace("'", "''") + "'";
  }

  private Table requireTable(FromItem item) {
    if (!(item instanceof Table t))
      throw new SqlCompilationException("0A000", "Only semantic tables are allowed in FROM/JOIN");
    return t;
  }

  private static String alias(Table t) {
    return unquote(t.getAlias() == null ? t.getName() : t.getAlias().getName());
  }

  private static String unquote(String v) {
    return v == null ? null : v.replaceAll("^\"|\"$", "");
  }

  private String q(String v) {
    return "\"" + v.replace("\"", "\"\"") + "\"";
  }

  private void fail(String state, String message) {
    throw new SqlCompilationException(state, message);
  }

  private enum Usage {
    SELECT,
    FILTER,
    GROUP,
    HAVING,
    ORDER,
    JOIN
  }

  private static class Resolved {
    final String alias;
    final SemanticModel.Dimension dimension;
    final SemanticModel.Metric metric;

    Resolved(String a, SemanticModel.Dimension d, SemanticModel.Metric m) {
      alias = a;
      dimension = d;
      metric = m;
    }
  }

  private static class Context {
    final SemanticModel model;
    final Map<String, SemanticModel.SemanticObject> objects = new LinkedHashMap<>();
    final List<CompiledQuery.Parameter> parameters = new ArrayList<>();
    final List<JoinEdge> relationships = new ArrayList<>();
    final Set<String> measureAliases = new LinkedHashSet<>();
    String baseAlias;

    Context(SemanticModel m) {
      model = m;
    }

    void add(Table table) {
      String schema = unquote(table.getSchemaName());
      var object = model.objects().get(unquote(table.getName()));
      if (object == null)
        throw new SqlCompilationException("42P01", "Unknown semantic object: " + table.getName());
      String domain = model.domain(object);
      String legacy = model.project().spec().semanticSchema();
      if (schema != null && !schema.equalsIgnoreCase(domain) && !schema.equalsIgnoreCase(legacy))
        throw new SqlCompilationException(
            "42P01", "Object " + object.metadata().name() + " belongs to domain schema " + domain);
      String objectAlias = alias(table);
      if (baseAlias == null) baseAlias = objectAlias;
      objects.put(objectAlias, object);
    }

    Resolved resolve(Column col) {
      String name = unquote(col.getColumnName());
      String requested = col.getTable() == null ? null : unquote(col.getTable().getName());
      List<Map.Entry<String, SemanticModel.SemanticObject>> scope =
          requested == null
              ? new ArrayList<>(objects.entrySet())
              : objects.entrySet().stream()
                  .filter(e -> e.getKey().equalsIgnoreCase(requested))
                  .toList();
      List<Resolved> found = new ArrayList<>();
      for (var e : scope) {
        var d = e.getValue().dimension(name);
        if (d.isPresent()) found.add(new Resolved(e.getKey(), d.get(), null));
        var m = model.metrics().get(name);
        if (m != null
            && m.spec().baseObject().equals(e.getValue().metadata().name())
            && model.domain(m).equals(model.domain(e.getValue())))
          found.add(new Resolved(e.getKey(), null, m));
      }
      if (found.isEmpty())
        throw new SqlCompilationException("42703", "Unknown semantic field: " + name);
      if (found.size() > 1)
        throw new SqlCompilationException("42702", "Ambiguous semantic field: " + name);
      return found.getFirst();
    }
  }

  private record JoinEdge(
      String sourceAlias,
      String targetAlias,
      String name,
      SemanticModel.Cardinality cardinality) {}
}
