package com.acme.semantic.mcp;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.core.SemanticPrincipal;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class McpTransportSecurity implements ServerTransportSecurityValidator {
  private final AuthenticationService authentication;

  McpTransportSecurity(AuthenticationService authentication) {
    this.authentication = authentication;
  }

  @Override
  public void validateHeaders(Map<String, List<String>> headers)
      throws ServerTransportSecurityException {
    if (authenticate(headers).isEmpty()) {
      throw new ServerTransportSecurityException(401, "Missing or invalid MCP credentials");
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

  Optional<SemanticPrincipal> authenticate(Map<String, List<String>> headers) {
    return authenticate(first(headers, "Authorization"), first(headers, "X-API-Key"));
  }

  Optional<SemanticPrincipal> authenticate(String authorization, String apiKey) {
    if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      Optional<SemanticPrincipal> principal =
          authentication.authenticateAccessToken(authorization.substring(7).trim());
      if (principal.isPresent()) return principal;
    }
    return authentication.authenticateApiKey(apiKey);
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
}
