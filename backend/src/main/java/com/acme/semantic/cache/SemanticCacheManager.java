package com.acme.semantic.cache;

import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.compiler.SemanticSqlCompiler;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SemanticCacheManager {
  private static final Logger log = LoggerFactory.getLogger(SemanticCacheManager.class);
  private static final String EMPTY_FINGERPRINT = SemanticCacheValues.fingerprint(List.of());

  private final SemanticResultCache cache;
  private final SemanticProperties.Cache config;

  @Autowired
  public SemanticCacheManager(SemanticResultCache cache, SemanticProperties properties) {
    this(
        cache,
        properties.cache() == null ? SemanticProperties.Cache.defaults() : properties.cache());
  }

  public SemanticCacheManager(SemanticResultCache cache, SemanticProperties.Cache config) {
    this.cache = cache;
    this.config = config == null ? SemanticProperties.Cache.defaults() : config;
  }

  public static SemanticCacheManager disabled() {
    return new SemanticCacheManager(new DisabledCache(), disabledConfig());
  }

  public CompiledQuery compile(
      String sourceSql,
      SemanticModel model,
      String authorizationFingerprint,
      SemanticSqlCompiler compiler) {
    if (!enabled(CacheKind.PLAN)) return freshCorrelation(compiler.compile(sourceSql, model));
    String authorization = normalizeFingerprint(authorizationFingerprint);
    PlanCacheLookup lookup =
        new PlanCacheLookup(
            model.revision(), SemanticCacheValues.fingerprint(sourceSql), authorization);
    Optional<CompiledQuery> cached = cache.getPlan(lookup, CompiledQuery.class);
    if (cached.isPresent()) return freshCorrelation(cached.get());
    CompiledQuery compiled = compiler.compile(sourceSql, model);
    CompiledQuery template = withoutCorrelation(compiled);
    SemanticCacheKey key =
        new SemanticCacheKey(
            CacheKind.PLAN,
            model.revision(),
            compiled.trinoSql(),
            SemanticCacheValues.fingerprint(
                compiled.parameters().stream().map(CompiledQuery.Parameter::index).toList()),
            authorization,
            SemanticCacheValues.fingerprint(sourceSql));
    cache.putPlan(lookup, key, template, SemanticCacheValues.estimatedBytes(template));
    return freshCorrelation(template);
  }

  public QueryResult execute(
      String modelRevision,
      CompiledQuery compiled,
      List<Object> parameters,
      String authorizationFingerprint,
      QueryExecutor executor) {
    if (!enabled(CacheKind.RESULT)) return executeUncached(compiled, parameters, executor);
    CompiledQuery executionPlan = freshCorrelation(compiled);
    SemanticCacheKey key =
        key(
            CacheKind.RESULT,
            modelRevision,
            executionPlan.trinoSql(),
            parameters,
            authorizationFingerprint,
            "");
    Optional<CachedQueryResult> cached = cache.get(key, CachedQueryResult.class);
    if (cached.isPresent()) {
      log.info(
          "semantic query cache hit correlationId={} rows={}",
          executionPlan.correlationId(),
          cached.get().rows().size());
      return cached.get().materialize(true, executionPlan.correlationId());
    }
    QueryResult result = executor.execute(executionPlan, parameters);
    QueryResult materialized =
        new QueryResult(
            result.columns(),
            result.rows(),
            result.queryId(),
            false,
            executionPlan.correlationId());
    CachedQueryResult candidate = CachedQueryResult.from(materialized);
    long estimatedBytes =
        SemanticCacheValues.estimatedBytes(candidate.columns())
            + SemanticCacheValues.estimatedBytes(candidate.rows());
    SemanticProperties.ResultLayer results = resultConfig();
    if (results.maxRows() > 0
        && results.maxBytes() > 0
        && candidate.rows().size() <= results.maxRows()
        && estimatedBytes <= results.maxBytes()) {
      cache.put(key, candidate, estimatedBytes);
    }
    return materialized;
  }

  public QueryResult executeUncached(
      CompiledQuery compiled, List<Object> parameters, QueryExecutor executor) {
    CompiledQuery executionPlan = freshCorrelation(compiled);
    QueryResult result = executor.execute(executionPlan, parameters);
    return new QueryResult(
        result.columns(),
        result.rows(),
        result.queryId(),
        false,
        executionPlan.correlationId());
  }

  public boolean enabled() {
    return config.enabled() && config.maxEntries() > 0 && config.maxBytes() > 0;
  }

  public boolean enabled(CacheKind kind) {
    if (!enabled()) return false;
    return switch (kind) {
      case PLAN -> layerTtl(config.plans(), SemanticProperties.Cache.defaults().plans()) > 0;
      case DIMENSION_VALUES ->
          layerTtl(
                  config.dimensionValues(),
                  SemanticProperties.Cache.defaults().dimensionValues())
              > 0;
      case RESULT -> resultConfig().ttlSeconds() > 0;
    };
  }

  public SemanticCacheKey key(
      CacheKind kind,
      String modelRevision,
      String compiledTrinoSql,
      List<Object> parameters,
      String authorizationFingerprint,
      String requestFingerprint) {
    return new SemanticCacheKey(
        kind,
        modelRevision,
        compiledTrinoSql,
        SemanticCacheValues.fingerprint(parameters),
        normalizeFingerprint(authorizationFingerprint),
        requestFingerprint);
  }

  public <T> Optional<T> get(SemanticCacheKey key, Class<T> type) {
    return cache.get(key, type);
  }

  public void put(SemanticCacheKey key, Object value) {
    cache.put(key, value, SemanticCacheValues.estimatedBytes(value));
  }

  public void invalidateAll() {
    cache.invalidateAll();
  }

  public static String emptyAuthorizationFingerprint() {
    return EMPTY_FINGERPRINT;
  }

  private SemanticProperties.ResultLayer resultConfig() {
    return config.results() == null
        ? SemanticProperties.Cache.defaults().results()
        : config.results();
  }

  private long layerTtl(
      SemanticProperties.Layer configured, SemanticProperties.Layer defaults) {
    return configured == null ? defaults.ttlSeconds() : configured.ttlSeconds();
  }

  private String normalizeFingerprint(String fingerprint) {
    return fingerprint == null || fingerprint.isBlank() ? EMPTY_FINGERPRINT : fingerprint;
  }

  private CompiledQuery freshCorrelation(CompiledQuery compiled) {
    return new CompiledQuery(
        compiled.trinoSql(),
        compiled.parameters(),
        compiled.columns(),
        UUID.randomUUID().toString(),
        compiled.domains());
  }

  private CompiledQuery withoutCorrelation(CompiledQuery compiled) {
    return new CompiledQuery(
        compiled.trinoSql(), compiled.parameters(), compiled.columns(), "", compiled.domains());
  }

  private static SemanticProperties.Cache disabledConfig() {
    return new SemanticProperties.Cache(
        false,
        0,
        0,
        new SemanticProperties.Layer(0),
        new SemanticProperties.Layer(0),
        new SemanticProperties.ResultLayer(0, 0, 0));
  }

  private record CachedQueryResult(
      List<QueryResult.Column> columns, List<List<Object>> rows) {
    private static CachedQueryResult from(QueryResult result) {
      return new CachedQueryResult(result.columns(), result.rows());
    }

    private CachedQueryResult {
      columns = List.copyOf(columns);
      rows = snapshotRows(rows);
    }

    private QueryResult materialize(boolean cacheHit, String correlationId) {
      return new QueryResult(columns, snapshotRows(rows), null, cacheHit, correlationId);
    }

    private static List<List<Object>> snapshotRows(List<List<Object>> rows) {
      return rows.stream()
          .map(
              row -> {
                List<Object> copy = new ArrayList<>();
                row.forEach(value -> copy.add(snapshotValue(value)));
                return Collections.unmodifiableList(copy);
              })
          .toList();
    }

    private static Object snapshotValue(Object value) {
      if (value instanceof byte[] bytes) return bytes.clone();
      if (value instanceof java.sql.Timestamp timestamp) {
        java.sql.Timestamp copy = new java.sql.Timestamp(timestamp.getTime());
        copy.setNanos(timestamp.getNanos());
        return copy;
      }
      if (value instanceof java.sql.Date date) return new java.sql.Date(date.getTime());
      if (value instanceof java.sql.Time time) return new java.sql.Time(time.getTime());
      if (value instanceof List<?> list)
        return list.stream().map(CachedQueryResult::snapshotValue).toList();
      if (value instanceof Map<?, ?> map) {
        Map<Object, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(snapshotValue(key), snapshotValue(item)));
        return Collections.unmodifiableMap(copy);
      }
      return value;
    }
  }

  private static final class DisabledCache implements SemanticResultCache {
    @Override
    public <T> Optional<T> get(SemanticCacheKey key, Class<T> type) {
      return Optional.empty();
    }

    @Override
    public <T> Optional<T> getPlan(PlanCacheLookup lookup, Class<T> type) {
      return Optional.empty();
    }

    @Override
    public void put(SemanticCacheKey key, Object value, long estimatedBytes) {}

    @Override
    public void putPlan(
        PlanCacheLookup lookup, SemanticCacheKey key, Object value, long estimatedBytes) {}

    @Override
    public void invalidateAll() {}

    @Override
    public long size(CacheKind kind) {
      return 0;
    }
  }
}
