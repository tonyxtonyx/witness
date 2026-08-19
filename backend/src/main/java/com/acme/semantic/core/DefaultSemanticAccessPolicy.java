package com.acme.semantic.core;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.SemanticModel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** RBAC policy shared by every semantic transport and service. */
@Component
public class DefaultSemanticAccessPolicy implements SemanticAccessPolicy {
  private final SemanticProperties.Mcp config;

  public DefaultSemanticAccessPolicy(SemanticProperties properties) {
    this.config = properties.mcp() == null ? SemanticProperties.Mcp.defaults() : properties.mcp();
  }

  @Override
  public void requireAuthenticated(SemanticPrincipal principal) {
    if (principal == null || !principal.authenticated()) {
      throw new SemanticException(
          SemanticErrorCode.ACCESS_DENIED,
          "Authentication is required to access semantic metadata");
    }
  }

  @Override
  public boolean canReadObject(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object) {
    return principal != null
        && object != null
        && principal.hasPermission(model.domain(object), SemanticPermission.READ);
  }

  @Override
  public boolean canReadMetric(
      SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
    return principal != null
        && metric != null
        && principal.hasPermission(model.domain(metric), SemanticPermission.READ);
  }

  @Override
  public boolean canReadDomain(SemanticPrincipal principal, String domain) {
    return principal != null && principal.hasPermission(domain, SemanticPermission.READ);
  }

  @Override
  public boolean canQueryObject(
      SemanticPrincipal principal,
      SemanticModel model,
      SemanticModel.SemanticObject object) {
    return principal != null
        && object != null
        && principal.hasPermission(model.domain(object), SemanticPermission.QUERY);
  }

  @Override
  public boolean canQueryDomain(SemanticPrincipal principal, String domain) {
    return principal != null && principal.hasPermission(domain, SemanticPermission.QUERY);
  }

  @Override
  public boolean canWriteDomain(SemanticPrincipal principal, String domain) {
    return principal != null && principal.hasPermission(domain, SemanticPermission.WRITE);
  }

  @Override
  public boolean canViewCompiledSql(SemanticPrincipal principal) {
    return config.exposeCompiledSql()
        && principal != null
        && principal.hasCapability(SemanticCapability.VIEW_COMPILED_SQL);
  }

  @Override
  public boolean canViewPhysicalLineage(SemanticPrincipal principal) {
    return config.exposePhysicalLineage()
        && principal != null
        && principal.hasCapability(SemanticCapability.VIEW_PHYSICAL_LINEAGE);
  }

  @Override
  public List<String> appliedPolicySummary(SemanticPrincipal principal) {
    if (principal == null || !principal.authenticated()) return List.of("Unauthenticated");
    if (principal.admin()) return List.of("Administrator: all domains, permissions, and capabilities");
    List<String> summary = new ArrayList<>();
    if (!principal.roles().isEmpty())
      summary.add("Roles: " + String.join(", ", principal.roles().stream().sorted().toList()));
    principal.domainPermissions().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            entry ->
                summary.add(
                    "Domain "
                        + entry.getKey()
                        + ": "
                        + String.join(", ", entry.getValue().stream().map(Enum::name).sorted().toList())));
    if (!principal.capabilities().isEmpty())
      summary.add(
          "Capabilities: "
              + String.join(", ", principal.capabilities().stream().map(Enum::name).sorted().toList()));
    return summary.isEmpty() ? List.of("Authenticated with no semantic grants") : List.copyOf(summary);
  }
}
