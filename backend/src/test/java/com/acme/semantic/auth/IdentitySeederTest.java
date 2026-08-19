package com.acme.semantic.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.config.WitnessAuthProperties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class IdentitySeederTest {
  @Test
  void seedsFreshDatabaseAndDoesNotDuplicateOrResetChangedAdminPassword() {
    DataSource dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:seed-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    SemanticProperties semantic =
        new SemanticProperties(
            "semantic-model", "seed-service-key", true, null, null, null, null);
    WitnessAuthProperties auth =
        new WitnessAuthProperties("0123456789abcdef0123456789abcdef", true, 60, 30);

    ApiKeyHasher apiKeys = apiKeys(auth, semantic);
    IdentitySeeder first = new IdentitySeeder(jdbc, passwords, apiKeys, semantic, auth);
    first.afterPropertiesSet();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='admin'", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE is_admin=TRUE", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_accounts WHERE name='semantic-api-key'", Long.class))
        .isEqualTo(1);
    WitnessAuthProperties locked =
        new WitnessAuthProperties("0123456789abcdef0123456789abcdef", false, 60, 30);
    assertThatThrownBy(
            () -> new IdentitySeeder(jdbc, passwords, apiKeys, semantic, locked).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("admin/admin");

    String changed = passwords.encode("changed-admin-password");
    jdbc.update(
        "UPDATE users SET password_hash=?,must_change_password=FALSE WHERE username='admin'",
        changed);
    new IdentitySeeder(jdbc, passwords, apiKeys, semantic, auth).afterPropertiesSet();

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='admin'", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE username='admin'", String.class))
        .isEqualTo(changed);
    assertThat(
            jdbc.queryForObject(
                "SELECT must_change_password FROM users WHERE username='admin'", Boolean.class))
        .isFalse();

    String rotated = apiKeys.hash("rotated-service-key");
    jdbc.update(
        "UPDATE service_accounts SET api_key_hash=? WHERE name='semantic-api-key'", rotated);
    new IdentitySeeder(jdbc, passwords, apiKeys, semantic, auth).afterPropertiesSet();
    assertThat(
            jdbc.queryForObject(
                "SELECT api_key_hash FROM service_accounts WHERE name='semantic-api-key'",
                String.class))
        .isEqualTo(rotated);
  }

  @Test
  void migratesBootstrapAndMarksOtherLegacyAccountsForExplicitRotation() {
    DataSource dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:key-migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO roles(name,description,is_admin) VALUES ('legacy-role','Legacy role',FALSE)");
    long role = jdbc.queryForObject("SELECT id FROM roles WHERE name='legacy-role'", Long.class);
    jdbc.update(
        "INSERT INTO service_accounts(name,api_key_hash,role_id,enabled) VALUES ('semantic-api-key',?,?,TRUE)",
        "$2a$10$bootstrapLegacyHash000000000000000000000000000000000",
        role);
    jdbc.update(
        "INSERT INTO service_accounts(name,api_key_hash,role_id,enabled) VALUES ('legacy-agent',?,?,TRUE)",
        "$2a$10$otherLegacyHash0000000000000000000000000000000000000",
        role);
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    assertThat(
            jdbc.queryForObject(
                "SELECT requires_rotation FROM service_accounts WHERE name='legacy-agent'",
                Boolean.class))
        .isTrue();
    BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    SemanticProperties semantic =
        new SemanticProperties(
            "semantic-model", "bootstrap-plaintext", true, null, null, null, null);
    WitnessAuthProperties auth =
        new WitnessAuthProperties("0123456789abcdef0123456789abcdef", true, 60, 30);
    ApiKeyHasher apiKeys = apiKeys(auth, semantic);
    new IdentitySeeder(jdbc, passwords, apiKeys, semantic, auth).afterPropertiesSet();

    String bootstrapHash =
        jdbc.queryForObject(
            "SELECT api_key_hash FROM service_accounts WHERE name='semantic-api-key'", String.class);
    assertThat(apiKeys.matches(apiKeys.hash("bootstrap-plaintext"), bootstrapHash)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT requires_rotation FROM service_accounts WHERE name='semantic-api-key'",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT requires_rotation FROM service_accounts WHERE name='legacy-agent'",
                Boolean.class))
        .isTrue();
  }

  private ApiKeyHasher apiKeys(
      WitnessAuthProperties auth, SemanticProperties semantic) {
    JwtSecretProvider secrets = new JwtSecretProvider(auth, semantic);
    secrets.afterPropertiesSet();
    return new ApiKeyHasher(secrets);
  }
}
