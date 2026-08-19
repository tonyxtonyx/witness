package com.acme.semantic.core;

import com.acme.semantic.model.SemanticModel;
import java.util.List;
import java.util.Set;

/** Authorization boundary shared by metadata, planning, lineage, and execution services. */
public interface SemanticAccessPolicy {
  void requireAuthenticated(SemanticPrincipal principal);

  default void requireAdmin(SemanticPrincipal principal) {
    requireAuthenticated(principal);
    if (!principal.admin()) {
      throw new SemanticException(SemanticErrorCode.ACCESS_DENIED, "Administrator access is required");
    }
  }

  boolean canReadObject(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object);

  boolean canReadMetric(
      SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric);

  default boolean canReadDomain(SemanticPrincipal principal, String domain) {
    return principal != null && principal.authenticated();
  }

  default boolean canQueryObject(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object) {
    return canReadObject(principal, model, object);
  }

  default boolean canQueryDomain(SemanticPrincipal principal, String domain) {
    return principal != null && principal.authenticated();
  }

  default boolean canWriteDomain(SemanticPrincipal principal, String domain) {
    return principal != null
        && principal.hasPermission(domain, SemanticPermission.WRITE);
  }

  default void requireQueryDomains(SemanticPrincipal principal, Set<String> domains) {
    requireAuthenticated(principal);
    requireReadableDomains(principal, domains);
    if (domains.stream().anyMatch(domain -> !canQueryDomain(principal, domain))) {
      throw new SemanticException(
          SemanticErrorCode.ACCESS_DENIED, "Query permission is required for every referenced domain");
    }
  }

  default void requireWriteDomain(SemanticPrincipal principal, String domain) {
    requireAuthenticated(principal);
    requireReadableDomains(principal, Set.of(domain));
    if (!canWriteDomain(principal, domain)) {
      throw new SemanticException(
          SemanticErrorCode.ACCESS_DENIED, "Write permission is required for this domain");
    }
  }

  default void requireReadableDomains(SemanticPrincipal principal, Set<String> domains) {
    if (domains.stream().anyMatch(domain -> !canReadDomain(principal, domain))) {
      throw new SemanticException(
          SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND,
          "The requested semantic resource was not found");
    }
  }

  default boolean canReadDimension(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object,
      SemanticModel.Dimension dimension) {
    return canReadObject(principal, model, object);
  }

  boolean canViewCompiledSql(SemanticPrincipal principal);

  boolean canViewPhysicalLineage(SemanticPrincipal principal);

  /** Typed row filters applied by Semantic Core to analytical and dimension-value queries. */
  default List<SemanticQuery.FilterCondition> requiredFilters(
      SemanticPrincipal principal, SemanticModel model) {
    return List.of();
  }

  default List<String> appliedPolicySummary(SemanticPrincipal principal) {
    return List.of("No resolved semantic grants");
  }
}
