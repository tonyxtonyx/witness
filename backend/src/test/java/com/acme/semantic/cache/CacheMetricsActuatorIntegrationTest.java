package com.acme.semantic.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cache-metrics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.api-key=cache-metrics-key",
      "semantic.allow-insecure-api-key=true",
      "semantic.pgwire.enabled=false",
      "semantic.gitlab.enabled=false",
      "semantic.mcp.enabled=false",
      "semantic.model-path=semantic-model",
      "semantic.trino.url=jdbc:trino://invalid:8080"
    })
@AutoConfigureObservability
class CacheMetricsActuatorIntegrationTest {
  @Autowired SemanticCacheManager cache;
  @Autowired TestRestTemplate http;
  @LocalServerPort int port;

  @Test
  void prometheusEndpointPublishesCacheCountersAndSize() {
    SemanticCacheKey key =
        cache.key(
            CacheKind.RESULT,
            "metrics-revision",
            "SELECT 1",
            List.of(),
            SemanticCacheManager.emptyAuthorizationFingerprint(),
            "metrics-request");
    cache.get(key, String.class);
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-API-Key", "cache-metrics-key");

    ResponseEntity<String> response =
        http.exchange(
            "/actuator/prometheus",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .contains("semantic_cache_misses_total{kind=\"result\"}")
        .contains("semantic_cache_size{kind=\"result\"}");
  }

  @Test
  void curlRequiresCredentialsForPrometheusButNotHealthProbes() throws Exception {
    assertThat(curlStatus("/actuator/prometheus", false)).isEqualTo("401");
    assertThat(curlStatus("/actuator/prometheus", true)).isEqualTo("200");
    assertThat(curlStatus("/actuator/health/readiness", false)).isEqualTo("200");
  }

  private String curlStatus(String path, boolean authenticated)
      throws IOException, InterruptedException {
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                "curl",
                "--silent",
                "--output",
                "/dev/null",
                "--write-out",
                "%{http_code}"));
    if (authenticated) {
      command.add("--header");
      command.add("X-API-Key: cache-metrics-key");
    }
    command.add("http://127.0.0.1:" + port + path);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    assertThat(process.waitFor()).isZero();
    return output;
  }
}
