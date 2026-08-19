package com.acme.semantic.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.core.SemanticPrincipal;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpTransportSecurityTest {
  private final AuthenticationService authentication = mock(AuthenticationService.class);
  private final McpTransportSecurity security = new McpTransportSecurity(authentication);

  @BeforeEach
  void setUp() {
    when(authentication.authenticateApiKey("secret"))
        .thenReturn(Optional.of(SemanticPrincipal.authenticated("service-account")));
    when(authentication.authenticateAccessToken("token"))
        .thenReturn(Optional.of(SemanticPrincipal.authenticated("user")));
  }

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

  @Test
  void acceptsBearerTokensAndRejectsInvalidCredentials() {
    assertThatCode(
            () ->
                security.validateHeaders(
                    Map.of(
                        "Authorization", List.of("Bearer token"),
                        "Host", List.of("localhost:8080"))))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                security.validateHeaders(
                    Map.of(
                        "Authorization", List.of("Bearer invalid"),
                        "Host", List.of("localhost:8080"))))
        .isInstanceOf(ServerTransportSecurityException.class)
        .extracting(exception -> ((ServerTransportSecurityException) exception).getStatusCode())
        .isEqualTo(401);
  }
}
