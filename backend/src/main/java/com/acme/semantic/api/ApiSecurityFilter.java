package com.acme.semantic.api;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.core.SemanticPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {
  private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  public static final String PRINCIPAL_ATTRIBUTE =
      "com.acme.semantic.authenticatedPrincipal";
  public static final String CORRELATION_ATTRIBUTE =
      "com.acme.semantic.correlationId";

  private final AuthenticationService authentication;
  private final ObjectMapper mapper;

  public ApiSecurityFilter(AuthenticationService authentication, ObjectMapper mapper) {
    this.authentication = authentication;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlation = request.getHeader("X-Correlation-ID");
    if (correlation == null || !CORRELATION_ID.matcher(correlation).matches())
      correlation = UUID.randomUUID().toString();
    response.setHeader("X-Correlation-ID", correlation);
    request.setAttribute(CORRELATION_ATTRIBUTE, correlation);
    MDC.put("correlationId", correlation);
    try {
      Optional<SemanticPrincipal> principal = resolve(request);
      principal.ifPresent(value -> request.setAttribute(PRINCIPAL_ATTRIBUTE, value));
      if (request.getRequestURI().startsWith("/api/")
          && !publicPath(request.getRequestURI())
          && principal.isEmpty()) {
        unauthorized(response, correlation);
        return;
      }
      if (request.getRequestURI().startsWith("/api/v1/admin/")
          && principal.isPresent()
          && !principal.get().admin()) {
        forbidden(response, correlation);
        return;
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }

  public static SemanticPrincipal principal(HttpServletRequest request) {
    Object identity = request.getAttribute(PRINCIPAL_ATTRIBUTE);
    if (identity instanceof SemanticPrincipal principal) return principal;
    return identity == null
        ? SemanticPrincipal.anonymous()
        : SemanticPrincipal.authenticated(identity.toString());
  }

  private Optional<SemanticPrincipal> resolve(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      Optional<SemanticPrincipal> principal =
          authentication.authenticateAccessToken(authorization.substring(7).trim());
      if (principal.isPresent()) return principal;
    }
    return authentication.authenticateApiKey(request.getHeader("X-API-Key"));
  }

  private boolean publicPath(String path) {
    return path.startsWith("/api/v1/auth/") || path.startsWith("/actuator/health");
  }

  private void unauthorized(HttpServletResponse response, String correlation) throws IOException {
    response.setStatus(401);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getWriter(),
        Map.of(
            "code", "UNAUTHORIZED",
            "message", "Missing or invalid credentials",
            "correlationId", correlation));
  }

  private void forbidden(HttpServletResponse response, String correlation) throws IOException {
    response.setStatus(403);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getWriter(),
        Map.of(
            "code", "FORBIDDEN",
            "message", "Access denied",
            "correlationId", correlation));
  }
}
