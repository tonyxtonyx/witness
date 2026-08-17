package com.acme.semantic.core;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.SemanticModel;
import org.springframework.stereotype.Component;

/**
 * Current API-key policy. The interface deliberately lives in Semantic Core so deployments can
 * replace this catalog-wide policy with tenant, row, or column policies without changing MCP.
 */
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
    return principal != null && principal.authenticated();
  }

  @Override
  public boolean canReadMetric(
      SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
    return principal != null && principal.authenticated();
  }

  @Override
  public boolean canViewCompiledSql(SemanticPrincipal principal) {
    return principal != null && principal.authenticated() && config.exposeCompiledSql();
  }

  @Override
  public boolean canViewPhysicalLineage(SemanticPrincipal principal) {
    return principal != null && principal.authenticated() && config.exposePhysicalLineage();
  }
}
