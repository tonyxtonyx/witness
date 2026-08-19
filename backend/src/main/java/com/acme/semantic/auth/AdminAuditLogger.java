package com.acme.semantic.auth;

import com.acme.semantic.core.SemanticPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdminAuditLogger {
  private static final Logger audit = LoggerFactory.getLogger("semantic.admin.audit");

  public void mutation(
      SemanticPrincipal actor, String action, String target, String outcome, String reason) {
    audit.info(
        "admin mutation actor={} action={} target={} outcome={} reason={}",
        actor.id(),
        action,
        target,
        outcome,
        reason == null ? "-" : reason);
  }
}
