package com.acme.semantic.api;

import com.acme.semantic.config.SemanticProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {
  private final SemanticProperties properties;

  public ApiSecurityFilter(SemanticProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlation = request.getHeader("X-Correlation-ID");
    if (correlation == null || correlation.isBlank()) correlation = UUID.randomUUID().toString();
    response.setHeader("X-Correlation-ID", correlation);
    MDC.put("correlationId", correlation);
    try {
      if (request.getRequestURI().startsWith("/api/")
          && !secureEquals(properties.apiKey(), request.getHeader("X-API-Key"))) {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response
            .getWriter()
            .write(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid"
                    + " X-API-Key\",\"correlationId\":\""
                    + correlation
                    + "\"}");
        return;
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }

  private boolean secureEquals(String expected, String supplied) {
    if (expected == null || supplied == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }
}
