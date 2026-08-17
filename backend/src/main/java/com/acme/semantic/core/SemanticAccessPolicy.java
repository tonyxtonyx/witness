package com.acme.semantic.core;

import com.acme.semantic.model.SemanticModel;
import java.util.List;

/** Authorization boundary shared by metadata, planning, lineage, and execution services. */
public interface SemanticAccessPolicy {
  void requireAuthenticated(SemanticPrincipal principal);

  boolean canReadObject(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object);

  boolean canReadMetric(
      SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric);

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
    return List.of("Authenticated catalog read policy");
  }
}
