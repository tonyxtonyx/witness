package com.acme.semantic.api;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
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

  private final SemanticProperties properties;
  private final ObjectMapper mapper;

  public ApiSecurityFilter(SemanticProperties properties, ObjectMapper mapper) {
    this.properties = properties;
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
      if (request.getRequestURI().startsWith("/api/")
          && !secureEquals(properties.apiKey(), request.getHeader("X-API-Key"))) {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(
            response.getWriter(),
            Map.of(
                "code", "UNAUTHORIZED",
                "message", "Missing or invalid X-API-Key",
                "correlationId", correlation));
        return;
      }
      if (request.getRequestURI().startsWith("/api/")) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, "api-key");
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

  private boolean secureEquals(String expected, String supplied) {
    if (expected == null || expected.isBlank() || supplied == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }
}
