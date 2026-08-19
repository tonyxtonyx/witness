package com.acme.semantic.core;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.compiler.SemanticSqlCompiler;
import com.acme.semantic.compiler.SqlCompilationException;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.execution.QueryExecutionException;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SemanticQueryService {
  private final SemanticCatalog catalog;
  private final SemanticAccessPolicy policy;
  private final SemanticSqlCompiler compiler;
  private final QueryExecutor executor;
  private final SemanticProperties.Mcp config;
  private final int engineMaxRows;

  public SemanticQueryService(
      SemanticCatalog catalog,
      SemanticAccessPolicy policy,
      SemanticSqlCompiler compiler,
      QueryExecutor executor,
      SemanticProperties properties) {
    this.catalog = catalog;
    this.policy = policy;
    this.compiler = compiler;
    this.executor = executor;
    this.config = properties.mcp() == null ? SemanticProperties.Mcp.defaults() : properties.mcp();
    this.engineMaxRows =
        properties.trino() == null ? 10_000 : Math.max(1, properties.trino().maxRows());
  }

  public CompilationResponse compile(
      SemanticPrincipal principal, SemanticQuery query, String traceId) {
    try {
      PlannedQuery planned =
          plan(principal, query, new PlanOptions(true, false, 0, null, null));
      return compilationResponse(principal, planned, traceId);
    } catch (SemanticException exception) {
      if (exception.code() == SemanticErrorCode.ACCESS_DENIED
          || exception.code() == SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND) {
        throw exception;
      }
      return invalidCompilation(query, exception, traceId);
    }
  }

  public MetricQueryResponse query(
      SemanticPrincipal principal, SemanticQuery query, String traceId) {
    PlannedQuery planned =
        plan(principal, query, new PlanOptions(true, false, 0, null, null));
    long started = System.nanoTime();
    QueryResult result = execute(planned);
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    int requested = planned.normalizedQuery().limit();
    boolean truncated = result.rows().size() > requested;
    List<List<Object>> rows =
        truncated ? List.copyOf(result.rows().subList(0, requested)) : List.copyOf(result.rows());
    List<ResultColumn> columns = new ArrayList<>();
    for (int i = 0; i < planned.outputColumns().size(); i++) {
      PlannedColumn semantic = planned.outputColumns().get(i);
      String physicalType =
          i < result.columns().size() ? result.columns().get(i).typeName() : semantic.type();
      columns.add(
          new ResultColumn(
              semantic.id(), physicalType, semantic.role(), semantic.unit(), semantic.nullable()));
    }
    String queryId = result.queryId() == null ? UUID.randomUUID().toString() : result.queryId();
    return new MetricQueryResponse(
        queryId,
        planned.model().revision(),
        List.copyOf(columns),
        rows,
        rows.size(),
        truncated,
        Map.of(),
        planned.warnings(),
        policy.appliedPolicySummary(principal),
        new Execution(durationMs, result.queryId()),
        traceId);
  }

  public DimensionValuesResponse dimensionValues(
      SemanticPrincipal principal, DimensionValuesRequest request, String traceId) {
    policy.requireAuthenticated(principal);
    SemanticModel model = catalog.model();
    SemanticIds.ResolvedDimension requestedDimension =
        SemanticIds.requireDimension(model, policy, principal, request.dimensionId());
    int limit = request.limit();
    int dimensionMaximum = Math.min(config.dimensionMaxRows(), Math.max(0, engineMaxRows - 1));
    if (limit < 1 || limit > dimensionMaximum) {
      throw new SemanticException(
          SemanticErrorCode.QUERY_LIMIT_EXCEEDED,
          "Dimension value limit must be between 1 and " + dimensionMaximum);
    }
    int offset = decodeCursor(request.cursor(), model.revision());
    boolean highCardinality = highCardinality(requestedDimension);
    boolean hasFilters = request.filters() != null && !request.filters().conditions().isEmpty();
    boolean hasSearch = request.search() != null && !request.search().isBlank();
    if (highCardinality && !hasFilters && !hasSearch) {
      throw new SemanticException(
          SemanticErrorCode.HIGH_CARDINALITY_SEARCH_REQUIRED,
          "This high-cardinality dimension requires search text or semantic filters",
          false,
          Map.of("dimension", request.dimensionId()),
          List.of("Provide search for text dimensions or a selective filters group"));
    }
    String normalizedType = normalizeType(requestedDimension.dimension().type());
    if (hasSearch && !isText(normalizedType)) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
          "Search text is supported only for string dimensions; use typed filters for this dimension");
    }
    List<String> metricIds = request.metricIds() == null ? List.of() : request.metricIds();
    SemanticQuery semanticQuery =
        new SemanticQuery(
            metricIds,
            List.of(new SemanticQuery.DimensionSelection(request.dimensionId(), null)),
            request.filters(),
            List.of(new SemanticQuery.OrderBy(request.dimensionId(), SemanticQuery.SortDirection.ASC)),
            limit,
            "UTC");
    ExtraCondition search =
        hasSearch
            ? new ExtraCondition(
                "STARTS_WITH(LOWER("
                    + memberReference(
                        model, requestedDimension.object(), requestedDimension.dimension())
                    + "), LOWER(?))",
                List.of(request.search().trim()))
            : null;
    PlannedQuery planned =
        plan(
            principal,
            semanticQuery,
            new PlanOptions(false, true, offset, search, request.dimensionId()));
    long started = System.nanoTime();
    QueryResult result = execute(planned);
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    boolean truncated = result.rows().size() > limit;
    List<List<Object>> rows =
        truncated ? result.rows().subList(0, limit) : result.rows();
    List<DimensionValue> values =
        rows.stream()
            .filter(row -> !row.isEmpty())
            .map(row -> new DimensionValue(row.getFirst(), Objects.toString(row.getFirst(), null)))
            .toList();
    String next = truncated ? encodeCursor(model.revision(), offset + values.size()) : null;
    return new DimensionValuesResponse(
        new DimensionDescriptor(request.dimensionId(), requestedDimension.dimension().type()),
        values,
        next,
        truncated,
        model.revision(),
        new Execution(
            (System.nanoTime() - started) / 1_000_000,
            result.queryId()),
        traceId);
  }

  private PlannedQuery plan(
      SemanticPrincipal principal, SemanticQuery query, PlanOptions options) {
    policy.requireAuthenticated(principal);
    if (query == null) invalid("Semantic query is required", "$");
    SemanticModel model = catalog.model();
    List<ResolvedMetric> metrics = resolveMetrics(principal, model, query.metrics());
    if (options.projectMetrics() && metrics.isEmpty()) {
      invalid("At least one metric is required", "$.metrics");
    }
    List<ResolvedDimension> dimensions =
        resolveDimensions(principal, model, query.dimensions());
    List<ResolvedFilter> filters = resolveFilters(principal, model, query.filters());
    List<ResolvedFilter> policyFilters =
        resolveFilters(
            principal,
            model,
            new SemanticQuery.FilterGroup(
                SemanticQuery.LogicalOperator.AND, policy.requiredFilters(principal, model)),
            true);
    List<ResolvedFilter> allFilters = new ArrayList<>(filters);
    allFilters.addAll(policyFilters);
    int requestedLimit = normalizeLimit(query.limit());
    String timezone = normalizeTimezone(query.timezone());
    SemanticModel.SemanticObject base =
        !metrics.isEmpty()
            ? metrics.getFirst().object()
            : !dimensions.isEmpty() ? dimensions.getFirst().object() : null;
    if (base == null) invalid("Query must select a metric or dimension", "$");
    LinkedHashSet<SemanticModel.SemanticObject> targets = new LinkedHashSet<>();
    metrics.forEach(metric -> targets.add(metric.object()));
    dimensions.forEach(dimension -> targets.add(dimension.object()));
    allFilters.forEach(filter -> targets.add(filter.dimension().object()));

    SemanticRelationshipGraph graph = new SemanticRelationshipGraph(model);
    ResolvedJoinPaths explicitPaths =
        resolveExplicitJoinPaths(principal, model, graph, base, targets, query.joinPaths());
    JoinResolution joinResolution =
        resolveJoins(principal, model, graph, base, targets, explicitPaths.paths());
    validateMetricCompatibility(
        model,
        graph,
        metrics,
        targets,
        joinResolution.paths(),
        explicitPaths.paths());
    SemanticQuery normalized =
        new SemanticQuery(
            metrics.stream().map(ResolvedMetric::id).toList(),
            dimensions.stream()
                .map(
                    dimension ->
                        new SemanticQuery.DimensionSelection(
                            dimension.id(), dimension.granularity()))
                .toList(),
            query.filters(),
            query.orderBy(),
            requestedLimit,
            timezone,
            explicitPaths.normalized());

    List<Object> parameters = new ArrayList<>();
    String semanticSql =
        renderSql(
            model,
            normalized,
            metrics,
            dimensions,
            allFilters,
            base,
            joinResolution.joins(),
            parameters,
            options,
            requestedLimit);
    CompiledQuery compiled;
    try {
      compiled = compiler.compile(semanticSql, model);
    } catch (SqlCompilationException exception) {
      SemanticErrorCode code =
          exception.sqlState().equals("42809") || exception.sqlState().equals("0A000")
              ? SemanticErrorCode.INCOMPATIBLE_METRICS_AND_DIMENSIONS
              : SemanticErrorCode.COMPILATION_FAILURE;
      throw new SemanticException(
          code,
          "Semantic query compilation failed: " + exception.getMessage(),
          false,
          Map.of("sqlState", exception.sqlState()),
          List.of("Use get_metric_context to select compatible dimensions"));
    }
    if (compiled.parameters().size() != parameters.size()) {
      throw new SemanticException(
          SemanticErrorCode.COMPILATION_FAILURE,
          "Internal semantic parameter binding mismatch");
    }
    List<PlannedColumn> output = new ArrayList<>();
    for (ResolvedDimension dimension : dimensions) {
      output.add(
          new PlannedColumn(
              dimension.id(),
              dimension.dimension().type(),
              "dimension",
              null,
              !Boolean.FALSE.equals(dimension.dimension().nullable())));
    }
    if (options.projectMetrics()) {
      for (ResolvedMetric metric : metrics) {
        output.add(
            new PlannedColumn(
                metric.id(),
                metric.metric().spec().resultType(),
                "metric",
                metric.metric().spec().format(),
                true));
      }
    }
    List<String> warnings = new ArrayList<>();
    if (!"UTC".equals(timezone)
        && dimensions.stream()
            .anyMatch(
                dimension ->
                    dimension.granularity() != null
                        && normalizeType(dimension.dimension().type()).equals("timestamp"))) {
      warnings.add(
          "Timestamps are assumed to be stored in UTC and are converted to "
              + timezone
              + " for time bucketing");
    }
    return new PlannedQuery(
        model,
        normalized,
        compiled,
        List.copyOf(parameters),
        metrics,
        dimensions,
        joinResolution.joins(),
        List.copyOf(output),
        List.copyOf(warnings));
  }

  private List<ResolvedMetric> resolveMetrics(
      SemanticPrincipal principal, SemanticModel model, List<String> ids) {
    List<String> values = ids == null ? List.of() : ids;
    if (values.size() > 20) invalid("A semantic query may contain at most 20 metrics", "$.metrics");
    Set<String> unique = new HashSet<>();
    List<ResolvedMetric> result = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      String id = values.get(i);
      if (!unique.add(id)) invalid("Duplicate metric ID: " + id, "$.metrics[" + i + "]");
      SemanticModel.Metric metric = SemanticIds.requireMetric(model, policy, principal, id);
      SemanticModel.SemanticObject object =
          model.resolveObject(metric.spec().baseObject(), model.domain(metric)).value();
      if (object == null || !policy.canReadObject(principal, model, object)) {
        throw new SemanticException(
            SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
            "Metric was not found or is not accessible: " + id);
      }
      result.add(new ResolvedMetric(id, metric, object));
    }
    return List.copyOf(result);
  }

  private List<ResolvedDimension> resolveDimensions(
      SemanticPrincipal principal,
      SemanticModel model,
      List<SemanticQuery.DimensionSelection> selections) {
    List<SemanticQuery.DimensionSelection> values =
        selections == null ? List.of() : selections;
    if (values.size() > 50) {
      invalid("A semantic query may contain at most 50 dimensions", "$.dimensions");
    }
    Set<String> unique = new HashSet<>();
    List<ResolvedDimension> result = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      SemanticQuery.DimensionSelection selection = values.get(i);
      if (selection == null || !unique.add(selection.id())) {
        invalid("Dimension IDs must be present and unique", "$.dimensions[" + i + "]");
      }
      SemanticIds.ResolvedDimension resolved =
          SemanticIds.requireDimension(model, policy, principal, selection.id());
      if (selection.granularity() != null && !temporal(resolved.dimension().type())) {
        invalid(
            "Time granularity is valid only for date or timestamp dimensions",
            "$.dimensions[" + i + "].granularity");
      }
      result.add(
          new ResolvedDimension(
              selection.id(), resolved.object(), resolved.dimension(), selection.granularity()));
    }
    return List.copyOf(result);
  }

  private List<ResolvedFilter> resolveFilters(
      SemanticPrincipal principal, SemanticModel model, SemanticQuery.FilterGroup group) {
    return resolveFilters(principal, model, group, false);
  }

  private List<ResolvedFilter> resolveFilters(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticQuery.FilterGroup group,
      boolean policyFilter) {
    if (group == null) return List.of();
    if (group.conditions().size() > 50) {
      invalid("A semantic query may contain at most 50 filter conditions", "$.filters.conditions");
    }
    List<ResolvedFilter> result = new ArrayList<>();
    for (int i = 0; i < group.conditions().size(); i++) {
      SemanticQuery.FilterCondition condition = group.conditions().get(i);
      String path = "$.filters.conditions[" + i + "]";
      if (condition == null || condition.operator() == null) {
        invalid("Filter member and operator are required", path);
      }
      SemanticIds.ResolvedDimension dimension =
          policyFilter
              ? SemanticIds.requirePolicyDimension(model, policy, principal, condition.member())
              : SemanticIds.requireDimension(model, policy, principal, condition.member());
      validateFilter(condition, dimension.dimension().type(), path);
      result.add(new ResolvedFilter(condition, dimension, policyFilter));
    }
    return List.copyOf(result);
  }

  private void validateFilter(
      SemanticQuery.FilterCondition filter, String type, String path) {
    int values = filter.values().size();
    switch (filter.operator()) {
      case IS_NULL, IS_NOT_NULL -> {
        if (values != 0) invalid("Null filters do not accept values", path + ".values");
      }
      case IN, NOT_IN -> {
        if (values == 0 || values > 100) {
          invalid("Set filters require between 1 and 100 values", path + ".values");
        }
      }
      case BETWEEN -> {
        if (values != 2) invalid("Between requires exactly two values", path + ".values");
      }
      default -> {
        if (values != 1) invalid("This filter requires exactly one value", path + ".values");
      }
    }
    for (int i = 0; i < filter.values().size(); i++) {
      if (!validValue(filter.values().get(i), type)) {
        invalid(
            "Filter value does not match member type " + type,
            path + ".values[" + i + "]");
      }
    }
  }

  private ResolvedJoinPaths resolveExplicitJoinPaths(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticRelationshipGraph graph,
      SemanticModel.SemanticObject base,
      Collection<SemanticModel.SemanticObject> targets,
      List<SemanticQuery.JoinPath> requested) {
    List<SemanticQuery.JoinPath> values = requested == null ? List.of() : requested;
    if (values.size() > 20) invalid("A semantic query may contain at most 20 join paths", "$.joinPaths");
    Set<String> targetIds = new LinkedHashSet<>();
    targets.forEach(target -> targetIds.add(model.objectId(target)));
    LinkedHashMap<String, List<SemanticRelationshipGraph.Edge>> paths = new LinkedHashMap<>();
    List<SemanticQuery.JoinPath> normalized = new ArrayList<>();
    String baseId = model.objectId(base);
    for (int i = 0; i < values.size(); i++) {
      SemanticQuery.JoinPath requestedPath = values.get(i);
      String path = "$.joinPaths[" + i + "]";
      if (requestedPath == null || requestedPath.to() == null || requestedPath.to().isBlank()) {
        invalid("Join path target is required", path + ".to");
      }
      SemanticModel.Resolution<SemanticModel.SemanticObject> resolution =
          model.resolveObject(requestedPath.to());
      if (resolution.ambiguous()) {
        invalid(
            "Join path target is ambiguous: " + String.join(", ", resolution.candidates()),
            path + ".to");
      }
      SemanticModel.SemanticObject target = resolution.value();
      if (target == null || !policy.canReadObject(principal, model, target)) {
        invalid("Join path target was not found or is not accessible", path + ".to");
      }
      String targetId = model.objectId(target);
      if (!targetIds.contains(targetId)) {
        invalid("Join path target is not used by this query: " + targetId, path + ".to");
      }
      if (paths.containsKey(targetId)) {
        invalid("Duplicate join path target: " + targetId, path + ".to");
      }
      if (requestedPath.via().size() > 20) {
        invalid("A join path may contain at most 20 relationships", path + ".via");
      }
      List<SemanticRelationshipGraph.Edge> edges = new ArrayList<>();
      String current = baseId;
      for (int hop = 0; hop < requestedPath.via().size(); hop++) {
        String relationship = requestedPath.via().get(hop);
        String hopPath = path + ".via[" + hop + "]";
        if (relationship == null || relationship.isBlank()) {
          invalid("Join path relationship names must be non-empty", hopPath);
        }
        List<SemanticRelationshipGraph.Edge> matches =
            graph.edgesFrom(current).stream()
                .filter(edge -> edge.relationship().name().equals(relationship))
                .toList();
        if (matches.size() != 1) {
          invalid(
              matches.isEmpty()
                  ? "Join path relationship is not connected at hop "
                      + hop
                      + ": "
                      + relationship
                  : "Join path relationship is ambiguous at hop "
                      + hop
                      + ": "
                      + relationship,
              hopPath);
        }
        SemanticRelationshipGraph.Edge edge = matches.getFirst();
        String next = edge.other(current);
        SemanticModel.SemanticObject nextObject = model.objectById(next).orElse(null);
        if (nextObject == null || !policy.canReadObject(principal, model, nextObject)) {
          invalid(
              "Join path relationship is not readable at hop " + hop + ": " + relationship,
              hopPath);
        }
        edges.add(edge);
        current = next;
      }
      if (!current.equals(targetId)) {
        int hop = requestedPath.via().size() - 1;
        invalid(
            hop < 0
                ? "Join path has no relationship hop to target " + targetId
                : "Join path does not reach target "
                    + targetId
                    + " at hop "
                    + hop
                    + ": "
                    + requestedPath.via().get(hop),
            hop < 0 ? path + ".via" : path + ".via[" + hop + "]");
      }
      paths.put(targetId, List.copyOf(edges));
      normalized.add(new SemanticQuery.JoinPath(targetId, requestedPath.via()));
    }
    return new ResolvedJoinPaths(Map.copyOf(paths), List.copyOf(normalized));
  }

  private JoinResolution resolveJoins(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticRelationshipGraph graph,
      SemanticModel.SemanticObject base,
      Collection<SemanticModel.SemanticObject> targets,
      Map<String, List<SemanticRelationshipGraph.Edge>> explicitPaths) {
    LinkedHashMap<String, JoinStep> joins = new LinkedHashMap<>();
    LinkedHashMap<String, List<SemanticRelationshipGraph.Edge>> paths = new LinkedHashMap<>();
    Set<String> joined = new LinkedHashSet<>();
    String baseId = model.objectId(base);
    joined.add(baseId);
    for (SemanticModel.SemanticObject target : targets) {
      String targetId = model.objectId(target);
      List<SemanticRelationshipGraph.Edge> path = explicitPaths.get(targetId);
      if (path == null) {
        SemanticRelationshipGraph.PathResult result =
            graph.uniqueShortestPath(baseId, targetId);
        if (result.ambiguous()) throw ambiguousJoinPath(result, baseId, targetId);
        path =
            result.path().orElseThrow(
                () ->
                    new SemanticException(
                        SemanticErrorCode.INCOMPATIBLE_METRICS_AND_DIMENSIONS,
                        "No semantic join path exists between requested members"));
      }
      paths.put(targetId, path);
      String current = baseId;
      for (SemanticRelationshipGraph.Edge edge : path) {
        String next = edge.other(current);
        SemanticModel.SemanticObject nextObject = model.objectById(next).orElse(null);
        if (nextObject == null || !policy.canReadObject(principal, model, nextObject)) {
          throw new SemanticException(
              SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
              "A required semantic object was not found or is not accessible");
        }
        if (!joined.contains(next)) {
          if (!joined.contains(current)) {
            throw new SemanticException(
                SemanticErrorCode.COMPILATION_FAILURE,
                "Semantic join plan is not connected");
          }
          joins.putIfAbsent(edge.id(), new JoinStep(current, next, edge));
          joined.add(next);
        }
        current = next;
      }
    }
    return new JoinResolution(List.copyOf(joins.values()), Map.copyOf(paths));
  }

  private void validateMetricCompatibility(
      SemanticModel model,
      SemanticRelationshipGraph graph,
      List<ResolvedMetric> metrics,
      Collection<SemanticModel.SemanticObject> targets,
      Map<String, List<SemanticRelationshipGraph.Edge>> paths,
      Map<String, List<SemanticRelationshipGraph.Edge>> explicitPaths) {
    for (ResolvedMetric metric : metrics) {
      for (SemanticModel.SemanticObject target : targets) {
        String metricObjectId = model.objectId(metric.object());
        String targetId = model.objectId(target);
        List<SemanticRelationshipGraph.Edge> path;
        if (explicitPaths.containsKey(metricObjectId) || explicitPaths.containsKey(targetId)) {
          path =
              pathBetween(
                  paths.getOrDefault(metricObjectId, List.of()),
                  paths.getOrDefault(targetId, List.of()));
        } else {
          SemanticRelationshipGraph.PathResult result =
              graph.uniqueShortestPath(metricObjectId, targetId);
          if (result.ambiguous()) throw ambiguousJoinPath(result, metricObjectId, targetId);
          path =
              result.path().orElseThrow(
                  () ->
                      new SemanticException(
                          SemanticErrorCode.INCOMPATIBLE_METRICS_AND_DIMENSIONS,
                          "Requested metrics and dimensions are not connected"));
        }
        if (!graph.fanoutSafe(model.objectId(metric.object()), path)) {
          throw new SemanticException(
              SemanticErrorCode.INCOMPATIBLE_METRICS_AND_DIMENSIONS,
              "A requested join path can duplicate rows behind metric " + metric.id(),
              false,
              Map.of("metric", metric.id()),
              List.of("Use get_metric_context to select a fan-out-safe dimension"));
        }
      }
    }
  }

  private List<SemanticRelationshipGraph.Edge> pathBetween(
      List<SemanticRelationshipGraph.Edge> left,
      List<SemanticRelationshipGraph.Edge> right) {
    int shared = 0;
    while (shared < left.size()
        && shared < right.size()
        && left.get(shared).id().equals(right.get(shared).id())) shared++;
    List<SemanticRelationshipGraph.Edge> path = new ArrayList<>();
    for (int i = left.size() - 1; i >= shared; i--) path.add(left.get(i));
    path.addAll(right.subList(shared, right.size()));
    return List.copyOf(path);
  }

  private SemanticException ambiguousJoinPath(
      SemanticRelationshipGraph.PathResult result, String from, String to) {
    List<List<String>> candidates =
        result.candidatePaths().stream()
            .map(path -> path.stream().map(edge -> edge.relationship().name()).toList())
            .toList();
    return new SemanticException(
        SemanticErrorCode.AMBIGUOUS_JOIN_PATH,
        "More than one shortest semantic join path exists between " + from + " and " + to,
        false,
        Map.of("from", from, "to", to, "candidatePaths", candidates),
        List.of("Set joinPaths with a to target and an exact via relationship-name list"));
  }

  private String renderSql(
      SemanticModel model,
      SemanticQuery query,
      List<ResolvedMetric> metrics,
      List<ResolvedDimension> dimensions,
      List<ResolvedFilter> filters,
      SemanticModel.SemanticObject base,
      List<JoinStep> joins,
      List<Object> parameters,
      PlanOptions options,
      int requestedLimit) {
    StringBuilder sql = new StringBuilder("SELECT ");
    if (options.distinct()) sql.append("DISTINCT ");
    List<String> projections = new ArrayList<>();
    for (ResolvedDimension dimension : dimensions) {
      projections.add(
          dimensionExpression(model, dimension, query.timezone())
              + " AS "
              + quoteIdentifier(dimension.id()));
    }
    if (options.projectMetrics()) {
      for (ResolvedMetric metric : metrics) {
        projections.add(
            objectAlias(model, metric.object())
                + "."
                + metric.metric().metadata().name()
                + " AS "
                + quoteIdentifier(metric.id()));
      }
    }
    if (projections.isEmpty()) invalid("Query has no projected members", "$");
    sql.append(String.join(", ", projections));
    sql.append(" FROM ")
        .append(model.domain(base))
        .append('.')
        .append(base.metadata().name())
        .append(' ')
        .append(objectAlias(model, base));
    for (JoinStep step : joins) {
      SemanticModel.SemanticObject target = model.objectById(step.toObject()).orElseThrow();
      SemanticModel.Relationship relationship = step.edge().relationship();
      sql.append(joinKeyword(relationship.defaultJoinType()))
          .append(model.domain(target))
          .append('.')
          .append(target.metadata().name())
          .append(' ')
          .append(objectAlias(model, target))
          .append(" ON ");
      List<String> equalities = new ArrayList<>();
      for (int i = 0; i < relationship.sourceFields().size(); i++) {
        equalities.add(
            objectAlias(step.edge().sourceObject())
                + "."
                + relationship.sourceFields().get(i)
                + " = "
                + objectAlias(step.edge().targetObject())
                + "."
                + relationship.targetFields().get(i));
      }
      sql.append(String.join(" AND ", equalities));
    }
    List<String> conditions = new ArrayList<>();
    for (ResolvedFilter filter : filters) {
      if (!filter.policyFilter()) conditions.add(renderFilter(model, filter, parameters));
    }
    if (!conditions.isEmpty()) {
      String operator =
          query.filters() != null
                  && query.filters().operator() == SemanticQuery.LogicalOperator.OR
              ? " OR "
              : " AND ";
      sql.append(" WHERE (").append(String.join(operator, conditions)).append(')');
      if (options.extraCondition() != null) {
        sql.append(" AND (").append(options.extraCondition().sql()).append(')');
        parameters.addAll(options.extraCondition().parameters());
      }
    } else if (options.extraCondition() != null) {
      sql.append(" WHERE (").append(options.extraCondition().sql()).append(')');
      parameters.addAll(options.extraCondition().parameters());
    }
    List<String> requiredConditions = new ArrayList<>();
    for (ResolvedFilter filter : filters) {
      if (filter.policyFilter()) requiredConditions.add(renderFilter(model, filter, parameters));
    }
    if (!requiredConditions.isEmpty()) {
      sql.append(conditions.isEmpty() && options.extraCondition() == null ? " WHERE " : " AND ")
          .append('(')
          .append(String.join(" AND ", requiredConditions))
          .append(')');
    }
    if (options.projectMetrics() && !dimensions.isEmpty()) {
      sql.append(" GROUP BY ")
          .append(
              String.join(
                  ", ",
                  dimensions.stream()
                      .map(value -> dimensionExpression(model, value, query.timezone()))
                      .toList()));
    }
    if (query.orderBy() != null && !query.orderBy().isEmpty()) {
      Map<String, String> selectable = new LinkedHashMap<>();
      dimensions.forEach(
          value ->
              selectable.put(
                  value.id(), dimensionExpression(model, value, query.timezone())));
      metrics.forEach(
          value ->
              selectable.put(
                  value.id(),
                  objectAlias(model, value.object())
                      + "."
                      + value.metric().metadata().name()));
      List<String> ordering = new ArrayList<>();
      for (int i = 0; i < query.orderBy().size(); i++) {
        SemanticQuery.OrderBy order = query.orderBy().get(i);
        String expression = selectable.get(order.member());
        if (expression == null) {
          invalid(
              "Order member must also be selected: " + order.member(),
              "$.orderBy[" + i + "].member");
        }
        ordering.add(
            expression
                + (order.direction() == SemanticQuery.SortDirection.DESC ? " DESC" : " ASC"));
      }
      sql.append(" ORDER BY ").append(String.join(", ", ordering));
    }
    if (options.offset() > 0) sql.append(" OFFSET ").append(options.offset());
    sql.append(" LIMIT ").append(requestedLimit + 1);
    return sql.toString();
  }

  private String renderFilter(
      SemanticModel model, ResolvedFilter resolved, List<Object> parameters) {
    SemanticQuery.FilterCondition filter = resolved.condition();
    String member =
        memberReference(model, resolved.dimension().object(), resolved.dimension().dimension());
    return switch (filter.operator()) {
      case IS_NULL -> member + " IS NULL";
      case IS_NOT_NULL -> member + " IS NOT NULL";
      case IN, NOT_IN -> {
        filter.values().stream()
            .map(value -> coerceValue(value, resolved.dimension().dimension().type()))
            .forEach(parameters::add);
        String placeholders = String.join(", ", java.util.Collections.nCopies(filter.values().size(), "?"));
        yield member
            + (filter.operator() == SemanticQuery.FilterOperator.NOT_IN ? " NOT IN (" : " IN (")
            + placeholders
            + ")";
      }
      case BETWEEN -> {
        filter.values().stream()
            .map(value -> coerceValue(value, resolved.dimension().dimension().type()))
            .forEach(parameters::add);
        yield member + " BETWEEN ? AND ?";
      }
      default -> {
        parameters.add(
            coerceValue(filter.values().getFirst(), resolved.dimension().dimension().type()));
        yield member + " " + comparison(filter.operator()) + " ?";
      }
    };
  }

  private QueryResult execute(PlannedQuery planned) {
    try {
      return executor.execute(planned.compiled(), planned.parameters());
    } catch (QueryExecutionException exception) {
      boolean timeout = "57014".equals(exception.sqlState());
      throw new SemanticException(
          timeout ? SemanticErrorCode.EXECUTION_TIMEOUT : SemanticErrorCode.EXECUTION_FAILURE,
          timeout ? "Semantic query execution timed out" : "Semantic query execution failed",
          !timeout,
          Map.of("sqlState", exception.sqlState()),
          List.of("Retry later or reduce query dimensions and filters"));
    }
  }

  private CompilationResponse compilationResponse(
      SemanticPrincipal principal, PlannedQuery planned, String traceId) {
    List<String> models = new ArrayList<>();
    models.add(
        SemanticIds.objectId(
            planned.model(),
            !planned.metrics().isEmpty()
                ? planned.metrics().getFirst().object()
                : planned.dimensions().getFirst().object()));
    for (JoinStep join : planned.joins()) {
      String id = join.toObject();
      if (!models.contains(id)) models.add(id);
    }
    String complexity =
        planned.joins().isEmpty() && planned.outputColumns().size() <= 3
            ? "low"
            : planned.joins().size() <= 2 ? "medium" : "high";
    return new CompilationResponse(
        true,
        planned.normalizedQuery(),
        planned.model().revision(),
        new QueryPlan(
            planned.metrics().stream().map(ResolvedMetric::id).toList(),
            planned.dimensions().stream().map(ResolvedDimension::id).toList(),
            models,
            planned.joins().stream().map(join -> join.edge().relationship().name()).toList(),
            complexity),
        policy.appliedPolicySummary(principal),
        planned.warnings(),
        List.of(),
        policy.canViewCompiledSql(principal) ? planned.compiled().trinoSql() : null,
        traceId);
  }

  private CompilationResponse invalidCompilation(
      SemanticQuery query, SemanticException exception, String traceId) {
    String path = Objects.toString(exception.details().get("path"), "$");
    return new CompilationResponse(
        false,
        query,
        catalog.model().revision(),
        new QueryPlan(List.of(), List.of(), List.of(), List.of(), "unknown"),
        List.of(),
        List.of(),
        List.of(
            new ValidationIssue(
                exception.code().name(),
                "ERROR",
                exception.getMessage(),
                path,
                null,
                exception.details(),
                exception.suggestions())),
        null,
        traceId);
  }

  private int normalizeLimit(Integer requested) {
    int limit = requested == null ? config.queryDefaultRows() : requested;
    int maximum = Math.min(config.queryMaxRows(), Math.max(0, engineMaxRows - 1));
    if (limit < 1 || limit > maximum) {
      throw new SemanticException(
          SemanticErrorCode.QUERY_LIMIT_EXCEEDED,
          "Query limit must be between 1 and " + maximum,
          false,
          Map.of("path", "$.limit", "maximum", maximum),
          List.of("Reduce the requested limit"));
    }
    return limit;
  }

  private String normalizeTimezone(String timezone) {
    String value = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
    try {
      return ZoneId.of(value).getId();
    } catch (DateTimeException exception) {
      invalid("Unknown IANA timezone: " + value, "$.timezone");
      return "UTC";
    }
  }

  private boolean validValue(Object value, String type) {
    if (value == null) return false;
    String normalized = normalizeType(type);
    if (Set.of("bigint", "integer", "int", "smallint").contains(normalized)) {
      if (!(value instanceof Number)) return false;
      try {
        return new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0;
      } catch (NumberFormatException ignored) {
        return false;
      }
    }
    if (Set.of("double", "real", "decimal", "numeric").contains(normalized)) {
      return value instanceof Number;
    }
    if (normalized.equals("boolean")) return value instanceof Boolean;
    if (normalized.equals("date")) {
      try {
        LocalDate.parse(value.toString());
        return value instanceof String;
      } catch (DateTimeException ignored) {
        return false;
      }
    }
    if (normalized.equals("timestamp")) {
      if (!(value instanceof String)) return false;
      try {
        LocalDate.parse(value.toString());
        return true;
      } catch (DateTimeException ignored) {
        try {
          LocalDateTime.parse(value.toString());
          return true;
        } catch (DateTimeException nested) {
          try {
            OffsetDateTime.parse(value.toString());
            return true;
          } catch (DateTimeException finalIgnored) {
            return false;
          }
        }
      }
    }
    return value instanceof String;
  }

  private Object coerceValue(Object value, String type) {
    String normalized = normalizeType(type);
    if (Set.of("bigint", "integer", "int", "smallint").contains(normalized)) {
      return ((Number) value).longValue();
    }
    if (Set.of("decimal", "numeric").contains(normalized)) {
      return new BigDecimal(value.toString());
    }
    if (Set.of("double", "real").contains(normalized)) {
      return ((Number) value).doubleValue();
    }
    if (normalized.equals("date")) {
      return java.sql.Date.valueOf(LocalDate.parse(value.toString()));
    }
    if (normalized.equals("timestamp")) {
      String text = value.toString();
      try {
        return java.sql.Timestamp.valueOf(LocalDate.parse(text).atStartOfDay());
      } catch (DateTimeException ignored) {
        try {
          return java.sql.Timestamp.valueOf(LocalDateTime.parse(text));
        } catch (DateTimeException nested) {
          return OffsetDateTime.parse(text);
        }
      }
    }
    return value;
  }

  private String dimensionExpression(
      SemanticModel model, ResolvedDimension dimension, String timezone) {
    String reference = memberReference(model, dimension.object(), dimension.dimension());
    if (dimension.granularity() == null) return reference;
    String truncated =
        "DATE_TRUNC('" + dimension.granularity().wire() + "', " + reference + ")";
    if ("UTC".equals(timezone)
        || !normalizeType(dimension.dimension().type()).equals("timestamp")) return truncated;
    String zone = timezone.replace("'", "''");
    return "CAST(DATE_TRUNC('"
        + dimension.granularity().wire()
        + "', AT_TIMEZONE(WITH_TIMEZONE("
        + reference
        + ", 'UTC'), '"
        + zone
        + "')) AS timestamp)";
  }

  private String memberReference(
      SemanticModel model,
      SemanticModel.SemanticObject object,
      SemanticModel.Dimension dimension) {
    return objectAlias(model, object) + "." + dimension.name();
  }

  private String objectAlias(SemanticModel model, SemanticModel.SemanticObject object) {
    return objectAlias(model.objectId(object));
  }

  private String objectAlias(String objectId) {
    int separator = objectId.indexOf('.');
    String domain = objectId.substring(0, separator);
    return "o_" + domain.length() + "_" + domain + "_" + objectId.substring(separator + 1);
  }

  private String joinKeyword(SemanticModel.JoinType type) {
    if (type == null) return " JOIN ";
    return switch (type) {
      case left -> " LEFT JOIN ";
      case right -> " RIGHT JOIN ";
      case full -> " FULL JOIN ";
      case inner -> " JOIN ";
    };
  }

  private String comparison(SemanticQuery.FilterOperator operator) {
    return switch (operator) {
      case EQ -> "=";
      case NEQ -> "<>";
      case GT -> ">";
      case GTE -> ">=";
      case LT -> "<";
      case LTE -> "<=";
      default -> throw new IllegalArgumentException("Not a comparison operator: " + operator);
    };
  }

  private boolean highCardinality(SemanticIds.ResolvedDimension dimension) {
    return dimension.object().spec().primaryKey().contains(dimension.dimension().name())
        || dimension.dimension().name().toLowerCase(Locale.ROOT).endsWith("_id");
  }

  private boolean temporal(String type) {
    String normalized = normalizeType(type);
    return normalized.equals("date") || normalized.equals("timestamp");
  }

  private boolean isText(String normalizedType) {
    return normalizedType.equals("varchar") || normalizedType.equals("text");
  }

  private String normalizeType(String type) {
    return type == null
        ? ""
        : type.toLowerCase(Locale.ROOT).replaceFirst("\\(.*", "");
  }

  private String quoteIdentifier(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private void invalid(String message, String path) {
    throw new SemanticException(
        SemanticErrorCode.INVALID_SEMANTIC_QUERY,
        message,
        false,
        Map.of("path", path),
        List.of());
  }

  private int decodeCursor(String cursor, String revision) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      String decoded =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int split = decoded.lastIndexOf('\n');
      int offset = Integer.parseInt(decoded.substring(split + 1));
      if (split <= 0 || !decoded.substring(0, split).equals(revision) || offset < 0 || offset > 10_000) {
        throw new IllegalArgumentException();
      }
      return offset;
    } catch (Exception exception) {
      throw new SemanticException(
          SemanticErrorCode.INVALID_TOOL_ARGUMENTS,
          "Dimension cursor is invalid, expired, or exceeds the pagination bound");
    }
  }

  private String encodeCursor(String revision, int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((revision + "\n" + offset).getBytes(StandardCharsets.UTF_8));
  }

  private record ResolvedMetric(
      String id, SemanticModel.Metric metric, SemanticModel.SemanticObject object) {}

  private record ResolvedDimension(
      String id,
      SemanticModel.SemanticObject object,
      SemanticModel.Dimension dimension,
      SemanticQuery.TimeGranularity granularity) {}

  private record ResolvedFilter(
      SemanticQuery.FilterCondition condition,
      SemanticIds.ResolvedDimension dimension,
      boolean policyFilter) {}

  private record JoinStep(
      String fromObject, String toObject, SemanticRelationshipGraph.Edge edge) {}

  private record ResolvedJoinPaths(
      Map<String, List<SemanticRelationshipGraph.Edge>> paths,
      List<SemanticQuery.JoinPath> normalized) {}

  private record JoinResolution(
      List<JoinStep> joins,
      Map<String, List<SemanticRelationshipGraph.Edge>> paths) {}

  private record ExtraCondition(String sql, List<Object> parameters) {}

  private record PlanOptions(
      boolean projectMetrics,
      boolean distinct,
      int offset,
      ExtraCondition extraCondition,
      String dimensionValueMember) {}

  private record PlannedColumn(
      String id, String type, String role, String unit, boolean nullable) {}

  private record PlannedQuery(
      SemanticModel model,
      SemanticQuery normalizedQuery,
      CompiledQuery compiled,
      List<Object> parameters,
      List<ResolvedMetric> metrics,
      List<ResolvedDimension> dimensions,
      List<JoinStep> joins,
      List<PlannedColumn> outputColumns,
      List<String> warnings) {}

  public record ValidationIssue(
      String code,
      String severity,
      String message,
      String path,
      String member,
      Map<String, Object> details,
      List<String> suggestions) {}

  public record QueryPlan(
      List<String> metrics,
      List<String> dimensions,
      List<String> models,
      List<String> joinPath,
      String estimatedComplexity) {}

  public record CompilationResponse(
      boolean valid,
      SemanticQuery normalizedQuery,
      String semanticRevision,
      QueryPlan plan,
      List<String> appliedPolicySummary,
      List<String> warnings,
      List<ValidationIssue> errors,
      String compiledSql,
      String traceId) {}

  public record ResultColumn(
      String name, String type, String role, String unit, boolean nullable) {}

  public record Execution(long durationMs, String engineQueryId) {}

  public record MetricQueryResponse(
      String queryId,
      String semanticRevision,
      List<ResultColumn> columns,
      List<List<Object>> rows,
      int rowCount,
      boolean truncated,
      Map<String, Object> freshness,
      List<String> warnings,
      List<String> appliedPolicySummary,
      Execution execution,
      String traceId) {}

  public record DimensionValuesRequest(
      String dimensionId,
      List<String> metricIds,
      String search,
      SemanticQuery.FilterGroup filters,
      int limit,
      String cursor) {}

  public record DimensionDescriptor(String id, String type) {}

  public record DimensionValue(Object value, String label) {}

  public record DimensionValuesResponse(
      DimensionDescriptor dimension,
      List<DimensionValue> values,
      String nextCursor,
      boolean truncated,
      String semanticRevision,
      Execution execution,
      String traceId) {}
}
