package com.acme.semantic.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.semantic.config.SemanticProperties;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpTransportSecurityTest {
  private final McpTransportSecurity security =
      new McpTransportSecurity(
          new SemanticProperties("semantic-model", "secret", null, null, null));

  @Test
  void requiresApiKeyOnEveryRequest() {
    assertThatThrownBy(() -> security.validateHeaders(Map.of("Host", List.of("localhost:8080"))))
        .isInstanceOf(ServerTransportSecurityException.class)
        .extracting(exception -> ((ServerTransportSecurityException) exception).getStatusCode())
        .isEqualTo(401);
  }

  @Test
  void rejectsDnsRebindingOriginAndAcceptsSameOrigin() {
    assertThatThrownBy(
            () ->
                security.validateHeaders(
                    Map.of(
                        "X-API-Key", List.of("secret"),
                        "Host", List.of("localhost:8080"),
                        "Origin", List.of("https://attacker.example"))))
        .isInstanceOf(ServerTransportSecurityException.class)
        .extracting(exception -> ((ServerTransportSecurityException) exception).getStatusCode())
        .isEqualTo(403);

    assertThatCode(
            () ->
                security.validateHeaders(
                    Map.of(
                        "X-API-Key", List.of("secret"),
                        "Host", List.of("localhost:8080"),
                        "Origin", List.of("http://localhost:8080"))))
        .doesNotThrowAnyException();
  }
}
