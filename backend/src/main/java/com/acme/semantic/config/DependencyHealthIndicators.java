package com.acme.semantic.config;

import com.acme.semantic.catalog.SemanticCatalog;
import java.sql.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

public final class DependencyHealthIndicators {
  private DependencyHealthIndicators() {}

  @Component("model")
  public static class ModelHealth implements HealthIndicator {
    private final SemanticCatalog catalog;

    public ModelHealth(SemanticCatalog c) {
      catalog = c;
    }

    public Health health() {
      var s = catalog.status();
      return (s.activeRevision() != null ? Health.up() : Health.down())
          .withDetail("activeRevision", s.activeRevision() == null ? "none" : s.activeRevision())
          .withDetail("lastCheck", String.valueOf(s.checkedAt()))
          .withDetail("message", s.message())
          .build();
    }
  }

  @Component("trino")
  public static class TrinoHealth implements HealthIndicator {
    private final SemanticProperties.Trino config;

    public TrinoHealth(SemanticProperties p) {
      config = p.trino();
    }

    public Health health() {
      try (Connection c =
              DriverManager.getConnection(config.url(), config.username(), config.password());
          Statement s = c.createStatement()) {
        s.setQueryTimeout(3);
        s.execute("SELECT 1");
        return Health.up().build();
      } catch (Exception e) {
        return Health.down().withDetail("error", e.getMessage()).build();
      }
    }
  }

  @Component("gitlab")
  public static class GitLabHealth implements HealthIndicator {
    private final SemanticProperties.Gitlab config;

    public GitLabHealth(SemanticProperties p) {
      config = p.gitlab();
    }

    public Health health() {
      return config.enabled()
          ? Health.unknown().withDetail("mode", "remote; verified during model polling").build()
          : Health.up().withDetail("mode", "local mock adapter").build();
    }
  }
}
