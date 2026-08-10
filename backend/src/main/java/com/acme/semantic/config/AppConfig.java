package com.acme.semantic.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SemanticProperties.class)
public class AppConfig {
  @Bean(name = "trinoDataSource", destroyMethod = "close")
  HikariDataSource trinoDataSource(SemanticProperties properties) {
    SemanticProperties.Trino trino = properties.trino();
    HikariConfig config = new HikariConfig();
    config.setPoolName("semantic-trino");
    config.setJdbcUrl(trino.url());
    config.setUsername(trino.username());
    config.setPassword(trino.password());
    config.setMaximumPoolSize(trino.poolSize());
    config.setMinimumIdle(Math.min(2, trino.poolSize()));
    config.setConnectionTimeout(trino.connectionTimeoutMs());
    config.setInitializationFailTimeout(-1);
    config.setReadOnly(true);
    return new HikariDataSource(config);
  }
}
