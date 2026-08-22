package com.acme.semantic.cache;

import java.util.Optional;

/** Backing-store boundary for plans and materialised semantic results. */
public interface SemanticResultCache {
  <T> Optional<T> get(SemanticCacheKey key, Class<T> type);

  <T> Optional<T> getPlan(PlanCacheLookup lookup, Class<T> type);

  void put(SemanticCacheKey key, Object value, long estimatedBytes);

  void putPlan(
      PlanCacheLookup lookup, SemanticCacheKey key, Object value, long estimatedBytes);

  void invalidateAll();

  long size(CacheKind kind);
}
