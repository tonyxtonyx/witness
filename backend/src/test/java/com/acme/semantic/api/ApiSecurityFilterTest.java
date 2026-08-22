package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.core.SemanticPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiSecurityFilterTest {
  private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private final ObjectMapper mapper = new ObjectMapper();
  private final AuthenticationService authentication = mock(AuthenticationService.class);
  private final ApiSecurityFilter filter = new ApiSecurityFilter(authentication, mapper);

  @Test
  void replacesUnsafeCorrelationIdAndSerializesValidUnauthorizedJson() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/model");
    request.addHeader("X-Correlation-ID", "caller\"controlled");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    String correlation = response.getHeader("X-Correlation-ID");
    assertThat(correlation).matches(CORRELATION_ID);
    assertThat(correlation).isNotEqualTo("caller\"controlled");
    assertThat(mapper.readTree(response.getContentAsString()).get("correlationId").asText())
        .isEqualTo(correlation);
  }

  @Test
  void preservesAcceptedCorrelationId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/model");
    request.addHeader("X-Correlation-ID", "trace.valid:123-abc");
    request.addHeader("X-API-Key", "test-secret");
    when(authentication.authenticateApiKey("test-secret"))
        .thenReturn(Optional.of(SemanticPrincipal.authenticated("service")));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("trace.valid:123-abc");
  }

  @Test
  void actuatorMetricsRequireAuthenticationButHealthProbesRemainPublic() throws Exception {
    MockHttpServletResponse metrics = filter("/actuator/prometheus");
    MockHttpServletResponse discovery = filter("/actuator");
    MockHttpServletResponse healthComponent = filter("/actuator/health/db");
    MockHttpServletResponse health = filter("/actuator/health/readiness");

    assertThat(metrics.getStatus()).isEqualTo(401);
    assertThat(discovery.getStatus()).isEqualTo(401);
    assertThat(healthComponent.getStatus()).isEqualTo(401);
    assertThat(health.getStatus()).isEqualTo(200);
  }

  private MockHttpServletResponse filter(String path) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
    return response;
  }
}
