package com.acme.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "semantic")
public record SemanticProperties(
    String modelPath,
    String apiKey,
    boolean allowInsecureApiKey,
    Pgwire pgwire,
    Trino trino,
    Gitlab gitlab,
    Mcp mcp) {
  @ConstructorBinding
  public SemanticProperties {}

  public SemanticProperties(
      String modelPath, String apiKey, Pgwire pgwire, Trino trino, Gitlab gitlab) {
    this(modelPath, apiKey, false, pgwire, trino, gitlab, null);
  }

  public SemanticProperties(
      String modelPath, String apiKey, Pgwire pgwire, Trino trino, Gitlab gitlab, Mcp mcp) {
    this(modelPath, apiKey, false, pgwire, trino, gitlab, mcp);
  }

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

  public record Mcp(
      boolean enabled,
      String endpoint,
      int searchMaxResults,
      int queryDefaultRows,
      int queryMaxRows,
      int dimensionDefaultRows,
      int dimensionMaxRows,
      int lineageMaxDepth,
      int lineageMaxNodes,
      boolean exposeCompiledSql,
      boolean exposePhysicalLineage) {
    public static Mcp defaults() {
      return new Mcp(true, "/api/mcp", 50, 100, 500, 20, 100, 5, 250, false, false);
    }
  }
}
