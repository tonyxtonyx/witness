package com.acme.semantic.core;

import java.util.Objects;

/** Trusted caller identity created by the transport, never from tool arguments. */
public record SemanticPrincipal(String id, boolean authenticated) {
  public SemanticPrincipal {
    id = Objects.requireNonNullElse(id, "anonymous");
  }

  public static SemanticPrincipal authenticated(String id) {
    return new SemanticPrincipal(id, true);
  }

  public static SemanticPrincipal anonymous() {
    return new SemanticPrincipal("anonymous", false);
  }
}
