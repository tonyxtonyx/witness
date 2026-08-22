package com.acme.semantic.cache;

import java.util.Objects;

/** Pre-compilation lookup identity; the stored plan key contains the resulting Trino SQL. */
public record PlanCacheLookup(
    String modelRevision, String sourceSqlFingerprint, String authorizationFingerprint) {
  public PlanCacheLookup {
    Objects.requireNonNull(modelRevision, "modelRevision");
    Objects.requireNonNull(sourceSqlFingerprint, "sourceSqlFingerprint");
    Objects.requireNonNull(authorizationFingerprint, "authorizationFingerprint");
  }
}
