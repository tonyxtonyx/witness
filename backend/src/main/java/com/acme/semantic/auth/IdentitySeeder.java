package com.acme.semantic.auth;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.config.WitnessAuthProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@DependsOn("flyway")
public class IdentitySeeder implements InitializingBean {
  private static final Logger log = LoggerFactory.getLogger(IdentitySeeder.class);
  private static final String ADMIN = "admin";
  private static final String SERVICE_ROLE = "bootstrap-service-account";
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final ApiKeyHasher apiKeys;
  private final SemanticProperties semantic;
  private final WitnessAuthProperties auth;

  public IdentitySeeder(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      ApiKeyHasher apiKeys,
      SemanticProperties semantic,
      WitnessAuthProperties auth) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.apiKeys = apiKeys;
    this.semantic = semantic;
    this.auth = auth;
  }

  @Override
  @Transactional
  public void afterPropertiesSet() {
    long adminRole = ensureRole(ADMIN, "Built-in Witness administrator", true);
    Long adminId = id("SELECT id FROM users WHERE username=?", ADMIN);
    if (adminId == null) {
      Instant now = Instant.now();
      jdbc.update(
          "INSERT INTO users(username,password_hash,provider,display_name,enabled,must_change_password,created_at,updated_at) VALUES (?,?,?,?,TRUE,TRUE,?,?)",
          ADMIN,
          passwords.encode(ADMIN),
          "local",
          "Witness Administrator",
          now,
          now);
      adminId = id("SELECT id FROM users WHERE username=?", ADMIN);
    }
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_roles WHERE user_id=? AND role_id=?",
            Long.class,
            adminId,
            adminRole)
        == 0) jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", adminId, adminRole);

    String adminHash =
        jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, adminId);
    if (adminHash != null && passwords.matches(ADMIN, adminHash)) {
      String warning =
          "Built-in admin still uses the default admin/admin credential and must change its password immediately";
      if (!auth.allowDefaultAdmin()) throw new IllegalStateException(warning);
      log.warn("SECURITY WARNING: {}", warning);
    }

    String apiKey = semantic.apiKey();
    if (apiKey != null && !apiKey.isBlank()) {
      long serviceRole = ensureRole(SERVICE_ROLE, "Compatibility role for semantic.api-key", false);
      for (String permission : new String[] {"READ", "QUERY", "WRITE"})
        ensureGrant(serviceRole, "*", permission);
      ensureCapability(serviceRole, "VIEW_COMPILED_SQL");
      ensureCapability(serviceRole, "VIEW_PHYSICAL_LINEAGE");
      Long accountId = id("SELECT id FROM service_accounts WHERE name=?", "semantic-api-key");
      if (accountId == null) {
        jdbc.update(
            "INSERT INTO service_accounts(name,api_key_hash,role_id,enabled,requires_rotation) VALUES (?,?,?,TRUE,FALSE)",
            "semantic-api-key",
            apiKeys.hash(apiKey),
            serviceRole);
      } else if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT requires_rotation FROM service_accounts WHERE id=?",
              Boolean.class,
              accountId))) {
        jdbc.update(
            "UPDATE service_accounts SET api_key_hash=?,requires_rotation=FALSE WHERE id=?",
            apiKeys.hash(apiKey),
            accountId);
        log.warn(
            "SECURITY MIGRATION: re-derived the semantic-api-key service account using the configured semantic.api-key credential");
      }
    }
    warnLegacyServiceAccounts();
  }

  private void warnLegacyServiceAccounts() {
    var legacy =
        jdbc.queryForList(
            "SELECT name FROM service_accounts WHERE requires_rotation=TRUE ORDER BY name",
            String.class);
    if (!legacy.isEmpty())
      log.warn(
          "SECURITY MIGRATION: legacy BCrypt service accounts {} cannot authenticate until an administrator rotates each key through POST /api/v1/admin/service-accounts/{id}/rotate",
          legacy);
  }

  private long ensureRole(String name, String description, boolean admin) {
    Long id = id("SELECT id FROM roles WHERE name=?", name);
    if (id != null) return id;
    jdbc.update(
        "INSERT INTO roles(name,description,is_admin) VALUES (?,?,?)", name, description, admin);
    return id("SELECT id FROM roles WHERE name=?", name);
  }

  private void ensureGrant(long role, String domain, String permission) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM role_domain_grants WHERE role_id=? AND domain=? AND permission=?",
            Long.class,
            role,
            domain,
            permission);
    if (count == 0)
      jdbc.update(
          "INSERT INTO role_domain_grants(role_id,domain,permission) VALUES (?,?,?)",
          role,
          domain,
          permission);
  }

  private void ensureCapability(long role, String capability) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM role_capabilities WHERE role_id=? AND capability=?",
            Long.class,
            role,
            capability);
    if (count == 0)
      jdbc.update(
          "INSERT INTO role_capabilities(role_id,capability) VALUES (?,?)", role, capability);
  }

  private Long id(String sql, Object argument) {
    return jdbc.query(sql, (rs, row) -> rs.getLong(1), argument).stream().findFirst().orElse(null);
  }
}
