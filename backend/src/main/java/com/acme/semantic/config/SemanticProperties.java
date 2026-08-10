package com.acme.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "semantic")
public record SemanticProperties(
    String modelPath, String apiKey, Pgwire pgwire, Trino trino, Gitlab gitlab) {
  public record Pgwire(
      boolean enabled,
      int port,
      String username,
      String password,
      int maxFrameBytes,
      int maxPreparedStatements) {}

  public record Trino(
      String url,
      String username,
      String password,
      int timeoutSeconds,
      int maxRows,
      int poolSize,
      long connectionTimeoutMs) {}

  public record Gitlab(
      boolean enabled,
      String baseUrl,
      String projectId,
      String token,
      String defaultBranch,
      String modelPath,
      long pollMs,
      int connectTimeoutSeconds,
      int readTimeoutSeconds) {}
}
