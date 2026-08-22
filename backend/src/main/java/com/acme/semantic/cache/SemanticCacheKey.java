package com.acme.semantic.cache;

import java.util.Objects;

/** Complete governed cache identity. Values are represented by deterministic SHA-256 digests. */
public record SemanticCacheKey(
    CacheKind kind,
    String modelRevision,
    String compiledTrinoSql,
    String boundParametersFingerprint,
    String authorizationFingerprint,
    String requestFingerprint) {
  public SemanticCacheKey {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(modelRevision, "modelRevision");
    Objects.requireNonNull(compiledTrinoSql, "compiledTrinoSql");
    Objects.requireNonNull(boundParametersFingerprint, "boundParametersFingerprint");
    Objects.requireNonNull(authorizationFingerprint, "authorizationFingerprint");
    requestFingerprint = requestFingerprint == null ? "" : requestFingerprint;
  }
}
