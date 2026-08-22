package com.acme.semantic.cache;

import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticIds;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.core.SemanticQuery;
import com.acme.semantic.model.SemanticModel;
import java.util.ArrayList;
import java.util.List;

/** Shared conservative fingerprint for raw-SQL transports. */
public final class SemanticAuthorizationFingerprints {
  private SemanticAuthorizationFingerprints() {}

  public static String forRawSql(
      SemanticAccessPolicy policy,
      SemanticPrincipal principal,
      SemanticModel model,
      String readableModelFingerprint) {
    List<String> predicates = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (SemanticQuery.FilterCondition filter : policy.requiredFilters(principal, model)) {
      SemanticIds.ResolvedDimension resolved =
          SemanticIds.requirePolicyDimension(model, policy, principal, filter.member());
      predicates.add(
          model.objectId(resolved.object())
              + "."
              + resolved.dimension().name()
              + " "
              + filter.operator().name()
              + " "
              + filter.values().size());
      values.addAll(filter.values());
    }
    String policies = SemanticCacheValues.authorizationFingerprint(predicates, values);
    return SemanticCacheValues.combineFingerprints(
        policies, readableModelFingerprint);
  }
}
