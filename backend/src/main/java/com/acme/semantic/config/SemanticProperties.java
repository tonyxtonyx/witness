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
    Mcp mcp,
    Cache cache) {
  @ConstructorBinding
  public SemanticProperties {}

  public SemanticProperties(
      String modelPath, String apiKey, Pgwire pgwire, Trino trino, Gitlab gitlab) {
    this(modelPath, apiKey, false, pgwire, trino, gitlab, null, null);
  }

  public SemanticProperties(
      String modelPath, String apiKey, Pgwire pgwire, Trino trino, Gitlab gitlab, Mcp mcp) {
    this(modelPath, apiKey, false, pgwire, trino, gitlab, mcp, null);
  }

  public SemanticProperties(
      String modelPath,
      String apiKey,
      boolean allowInsecureApiKey,
      Pgwire pgwire,
      Trino trino,
      Gitlab gitlab,
      Mcp mcp) {
    this(modelPath, apiKey, allowInsecureApiKey, pgwire, trino, gitlab, mcp, null);
  }

  public record Pgwire(
      boolean enabled,
      int port,
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

  public record Cache(
      boolean enabled,
      int maxEntries,
      long maxBytes,
      Layer plans,
      Layer dimensionValues,
      ResultLayer results) {
    public static Cache defaults() {
      return new Cache(
          true,
          1_000,
          64L * 1024 * 1024,
          new Layer(600),
          new Layer(300),
          new ResultLayer(30, 1_000, 1024L * 1024));
    }
  }

  public record Layer(long ttlSeconds) {}

  public record ResultLayer(long ttlSeconds, int maxRows, long maxBytes) {}
}
