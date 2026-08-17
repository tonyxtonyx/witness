package com.acme.semantic.mcp;

import com.acme.semantic.config.SemanticProperties;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

final class McpTransportSecurity implements ServerTransportSecurityValidator {
  private final String apiKey;

  McpTransportSecurity(SemanticProperties properties) {
    this.apiKey = properties.apiKey();
  }

  @Override
  public void validateHeaders(Map<String, List<String>> headers)
      throws ServerTransportSecurityException {
    String suppliedKey = first(headers, "X-API-Key");
    if (!secureEquals(apiKey, suppliedKey)) {
      throw new ServerTransportSecurityException(401, "Missing or invalid MCP API key");
    }
    String origin = first(headers, "Origin");
    if (origin == null || origin.isBlank()) return;
    String host = first(headers, "Host");
    try {
      URI originUri = URI.create(origin);
      if (host == null
          || originUri.getRawAuthority() == null
          || !originUri.getRawAuthority().equalsIgnoreCase(host)) {
        throw new ServerTransportSecurityException(403, "MCP Origin does not match Host");
      }
    } catch (IllegalArgumentException exception) {
      throw new ServerTransportSecurityException(403, "Invalid MCP Origin header");
    }
  }

  private String first(Map<String, List<String>> headers, String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .filter(values -> values != null && !values.isEmpty())
        .map(List::getFirst)
        .findFirst()
        .orElse(null);
  }

  private boolean secureEquals(String expected, String supplied) {
    if (expected == null || supplied == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  boolean authenticated(String supplied) {
    return secureEquals(apiKey, supplied);
  }
}
