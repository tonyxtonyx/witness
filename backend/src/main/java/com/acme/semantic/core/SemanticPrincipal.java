package com.acme.semantic.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Trusted caller identity created by a transport, never from request or tool arguments. */
public record SemanticPrincipal(
    String id,
    String username,
    String displayName,
    String provider,
    boolean authenticated,
    boolean serviceAccount,
    boolean admin,
    Set<String> roles,
    Map<String, Set<SemanticPermission>> domainPermissions,
    Set<SemanticCapability> capabilities) {
  public SemanticPrincipal {
    id = Objects.requireNonNullElse(id, "anonymous");
    username = Objects.requireNonNullElse(username, id);
    displayName = Objects.requireNonNullElse(displayName, username);
    provider = Objects.requireNonNullElse(provider, "anonymous");
    roles = roles == null ? Set.of() : Set.copyOf(roles);
    Map<String, Set<SemanticPermission>> permissions = new LinkedHashMap<>();
    if (domainPermissions != null) {
      domainPermissions.forEach(
          (domain, grants) -> permissions.put(domain, grants == null ? Set.of() : Set.copyOf(grants)));
    }
    domainPermissions = Map.copyOf(permissions);
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
  }

  public static SemanticPrincipal authenticated(String id) {
    return new SemanticPrincipal(
        id, id, id, "transport", true, false, false, Set.of(), Map.of(), Set.of());
  }

  public static SemanticPrincipal user(
      long id,
      String username,
      String displayName,
      String provider,
      boolean admin,
      Set<String> roles,
      Map<String, Set<SemanticPermission>> permissions,
      Set<SemanticCapability> capabilities) {
    return new SemanticPrincipal(
        "user:" + id,
        username,
        displayName,
        provider,
        true,
        false,
        admin,
        roles,
        permissions,
        capabilities);
  }

  public static SemanticPrincipal serviceAccount(
      long id,
      String name,
      boolean admin,
      Set<String> roles,
      Map<String, Set<SemanticPermission>> permissions,
      Set<SemanticCapability> capabilities) {
    return new SemanticPrincipal(
        "service-account:" + id,
        name,
        name,
        "service-account",
        true,
        true,
        admin,
        roles,
        permissions,
        capabilities);
  }

  public static SemanticPrincipal anonymous() {
    return new SemanticPrincipal(
        "anonymous",
        "anonymous",
        "Anonymous",
        "anonymous",
        false,
        false,
        false,
        Set.of(),
        Map.of(),
        Set.of());
  }

  public boolean hasPermission(String domain, SemanticPermission permission) {
    if (!authenticated) return false;
    if (admin) return true;
    return domainPermissions.getOrDefault("*", Set.of()).contains(permission)
        || domainPermissions.getOrDefault(domain, Set.of()).contains(permission);
  }

  public boolean hasCapability(SemanticCapability capability) {
    return authenticated && (admin || capabilities.contains(capability));
  }
}
