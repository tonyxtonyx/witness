package com.acme.semantic.cache;

import com.acme.semantic.config.SemanticProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InProcessSemanticResultCache implements SemanticResultCache {
  private final SemanticProperties.Cache config;
  private final Clock clock;
  private final LinkedHashMap<SemanticCacheKey, Entry> entries =
      new LinkedHashMap<>(16, 0.75f, true);
  private final Map<PlanCacheLookup, SemanticCacheKey> planLookups = new HashMap<>();
  private final Map<CacheKind, Counter> hits = new EnumMap<>(CacheKind.class);
  private final Map<CacheKind, Counter> misses = new EnumMap<>(CacheKind.class);
  private final Map<CacheKind, Counter> evictions = new EnumMap<>(CacheKind.class);
  private long bytes;

  @Autowired
  public InProcessSemanticResultCache(
      SemanticProperties properties, MeterRegistry meterRegistry) {
    this(
        properties.cache() == null ? SemanticProperties.Cache.defaults() : properties.cache(),
        meterRegistry,
        Clock.systemUTC());
  }

  public InProcessSemanticResultCache(
      SemanticProperties.Cache config, MeterRegistry meterRegistry, Clock clock) {
    this.config = config == null ? SemanticProperties.Cache.defaults() : config;
    this.clock = clock;
    for (CacheKind kind : CacheKind.values()) {
      String tag = kind.metricTag();
      hits.put(kind, Counter.builder("semantic.cache.hits").tag("kind", tag).register(meterRegistry));
      misses.put(kind, Counter.builder("semantic.cache.misses").tag("kind", tag).register(meterRegistry));
      evictions.put(
          kind, Counter.builder("semantic.cache.evictions").tag("kind", tag).register(meterRegistry));
      meterRegistry.gauge(
          "semantic.cache.size", Tags.of("kind", tag), this, cache -> cache.size(kind));
    }
  }

  @Override
  public synchronized <T> Optional<T> get(SemanticCacheKey key, Class<T> type) {
    return getEntry(key, type, true);
  }

  @Override
  public synchronized <T> Optional<T> getPlan(PlanCacheLookup lookup, Class<T> type) {
    if (!enabled(CacheKind.PLAN)) return Optional.empty();
    SemanticCacheKey key = planLookups.get(lookup);
    if (key == null) {
      misses.get(CacheKind.PLAN).increment();
      return Optional.empty();
    }
    Optional<T> value = getEntry(key, type, false);
    if (value.isEmpty()) planLookups.remove(lookup);
    return value;
  }

  @Override
  public synchronized void put(SemanticCacheKey key, Object value, long estimatedBytes) {
    putEntry(key, value, estimatedBytes);
  }

  @Override
  public synchronized void putPlan(
      PlanCacheLookup lookup, SemanticCacheKey key, Object value, long estimatedBytes) {
    if (!putEntry(key, value, estimatedBytes)) return;
    planLookups.put(lookup, key);
  }

  @Override
  public synchronized void invalidateAll() {
    entries.clear();
    planLookups.clear();
    bytes = 0;
  }

  @Override
  public synchronized long size(CacheKind kind) {
    purgeExpired();
    return entries.keySet().stream().filter(key -> key.kind() == kind).count();
  }

  private <T> Optional<T> getEntry(SemanticCacheKey key, Class<T> type, boolean recordMiss) {
    if (!enabled(key.kind())) return Optional.empty();
    Entry entry = entries.get(key);
    if (entry == null) {
      if (recordMiss) misses.get(key.kind()).increment();
      return Optional.empty();
    }
    if (!entry.expiresAt().isAfter(clock.instant())) {
      remove(key, entry, true);
      if (recordMiss) misses.get(key.kind()).increment();
      else misses.get(CacheKind.PLAN).increment();
      return Optional.empty();
    }
    if (!type.isInstance(entry.value())) {
      remove(key, entry, true);
      if (recordMiss) misses.get(key.kind()).increment();
      return Optional.empty();
    }
    hits.get(key.kind()).increment();
    return Optional.of(type.cast(entry.value()));
  }

  private boolean putEntry(SemanticCacheKey key, Object value, long estimatedBytes) {
    if (!enabled(key.kind()) || value == null) return false;
    long weight =
        Math.max(1, estimatedBytes)
            + SemanticCacheValues.estimatedBytes(key.compiledTrinoSql())
            + SemanticCacheValues.estimatedBytes(key.boundParametersFingerprint())
            + SemanticCacheValues.estimatedBytes(key.authorizationFingerprint())
            + SemanticCacheValues.estimatedBytes(key.requestFingerprint());
    if (weight > maxBytes() || maxEntries() <= 0) return false;
    Entry previous = entries.remove(key);
    if (previous != null) bytes -= previous.estimatedBytes();
    entries.put(key, new Entry(value, weight, clock.instant().plusSeconds(ttlSeconds(key.kind()))));
    bytes += weight;
    evictToBounds();
    return entries.containsKey(key);
  }

  private void evictToBounds() {
    purgeExpired();
    Iterator<Map.Entry<SemanticCacheKey, Entry>> iterator = entries.entrySet().iterator();
    while ((entries.size() > maxEntries() || bytes > maxBytes()) && iterator.hasNext()) {
      Map.Entry<SemanticCacheKey, Entry> eldest = iterator.next();
      iterator.remove();
      bytes -= eldest.getValue().estimatedBytes();
      removePlanLookup(eldest.getKey());
      evictions.get(eldest.getKey().kind()).increment();
    }
  }

  private void purgeExpired() {
    Instant now = clock.instant();
    Iterator<Map.Entry<SemanticCacheKey, Entry>> iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<SemanticCacheKey, Entry> candidate = iterator.next();
      if (!candidate.getValue().expiresAt().isAfter(now)) {
        iterator.remove();
        bytes -= candidate.getValue().estimatedBytes();
        removePlanLookup(candidate.getKey());
        evictions.get(candidate.getKey().kind()).increment();
      }
    }
  }

  private void remove(SemanticCacheKey key, Entry entry, boolean eviction) {
    entries.remove(key);
    bytes -= entry.estimatedBytes();
    removePlanLookup(key);
    if (eviction) evictions.get(key.kind()).increment();
  }

  private void removePlanLookup(SemanticCacheKey key) {
    if (key.kind() == CacheKind.PLAN) planLookups.values().removeIf(key::equals);
  }

  private boolean enabled(CacheKind kind) {
    return config.enabled() && ttlSeconds(kind) > 0 && maxBytes() > 0;
  }

  private long ttlSeconds(CacheKind kind) {
    SemanticProperties.Cache defaults = SemanticProperties.Cache.defaults();
    return switch (kind) {
      case PLAN ->
          config.plans() == null
              ? defaults.plans().ttlSeconds()
              : config.plans().ttlSeconds();
      case DIMENSION_VALUES ->
          config.dimensionValues() == null
              ? defaults.dimensionValues().ttlSeconds()
              : config.dimensionValues().ttlSeconds();
      case RESULT ->
          config.results() == null ? defaults.results().ttlSeconds() : config.results().ttlSeconds();
    };
  }

  private int maxEntries() {
    return config.maxEntries();
  }

  private long maxBytes() {
    return config.maxBytes();
  }

  private record Entry(Object value, long estimatedBytes, Instant expiresAt) {}
}
