package com.acme.semantic.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class McpAuditLogger {
  private static final Logger log = LoggerFactory.getLogger("semantic.mcp.audit");

  void record(
      String principal,
      String tool,
      String revision,
      long durationMs,
      String status,
      String traceId,
      String queryId) {
    log.info(
        "mcp tool audit principal={} tool={} semanticRevision={} durationMs={} status={} traceId={} queryId={}",
        principal,
        tool,
        revision,
        durationMs,
        status,
        traceId,
        queryId);
  }
}
