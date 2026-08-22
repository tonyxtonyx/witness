package com.acme.semantic.auth;

import com.acme.semantic.core.SemanticCapability;
import com.acme.semantic.core.SemanticPermission;
import com.acme.semantic.core.SemanticPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
  private final JdbcTemplate jdbc;

  public IdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UserRecord> findUserByUsername(String username) {
    return jdbc.query(
            "SELECT id,username,password_hash,provider,display_name,email,enabled,must_change_password FROM users WHERE LOWER(username)=LOWER(?)",
            this::user,
            username)
        .stream()
        .findFirst();
  }

  public Optional<UserRecord> findUserById(long id) {
    return jdbc.query(
            "SELECT id,username,password_hash,provider,display_name,email,enabled,must_change_password FROM users WHERE id=?",
            this::user,
            id)
        .stream()
        .findFirst();
  }

  public Optional<SemanticPrincipal> resolveUser(long id) {
    Optional<UserRecord> user = findUserById(id);
    if (user.isEmpty() || !user.get().enabled()) return Optional.empty();
    List<RoleRecord> roles =
        jdbc.query(
            "SELECT r.id,r.name,r.is_admin FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.name",
            (rs, row) -> new RoleRecord(rs.getLong(1), rs.getString(2), rs.getBoolean(3)),
            id);
    Grants grants = grants(roles);
    UserRecord value = user.get();
    return Optional.of(
        SemanticPrincipal.user(
            value.id(),
            value.username(),
            value.displayName(),
            value.provider(),
            grants.admin(),
            grants.roles(),
            grants.permissions(),
            grants.capabilities()));
  }

  public Optional<ServiceAccountRecord> findEnabledServiceAccountByHash(String hash) {
    return jdbc.query(
        "SELECT sa.id,sa.name,sa.api_key_hash,r.id,r.name,r.is_admin FROM service_accounts sa JOIN roles r ON r.id=sa.role_id WHERE sa.api_key_hash=? AND sa.enabled=TRUE AND sa.requires_rotation=FALSE",
        (rs, row) ->
            new ServiceAccountRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                new RoleRecord(rs.getLong(4), rs.getString(5), rs.getBoolean(6))),
        hash)
        .stream()
        .findFirst();
  }

  public Optional<ServiceAccountRecord> findEnabledServiceAccountById(long id) {
    return jdbc.query(
        "SELECT sa.id,sa.name,sa.api_key_hash,r.id,r.name,r.is_admin FROM service_accounts sa JOIN roles r ON r.id=sa.role_id WHERE sa.id=? AND sa.enabled=TRUE AND sa.requires_rotation=FALSE",
        (rs, row) ->
            new ServiceAccountRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                new RoleRecord(rs.getLong(4), rs.getString(5), rs.getBoolean(6))),
        id)
        .stream()
        .findFirst();
  }

  public SemanticPrincipal resolveServiceAccount(ServiceAccountRecord account) {
    Grants grants = grants(List.of(account.role()));
    return SemanticPrincipal.serviceAccount(
        account.id(),
        account.name(),
        grants.admin(),
        grants.roles(),
        grants.permissions(),
        grants.capabilities());
  }

  public List<String> roleNames(long userId) {
    return jdbc.queryForList(
        "SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.name",
        String.class,
        userId);
  }

  public void updatePassword(long userId, String hash) {
    jdbc.update(
        "UPDATE users SET password_hash=?,must_change_password=FALSE,updated_at=? WHERE id=?",
        hash,
        Instant.now(),
        userId);
  }

  public long insertRefreshToken(long userId, String hash, Instant issued, Instant expires) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO refresh_tokens(user_id,token_hash,issued_at,expires_at) VALUES (?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          statement.setLong(1, userId);
          statement.setString(2, hash);
          statement.setObject(3, issued);
          statement.setObject(4, expires);
          return statement;
        },
        keys);
    if (keys.getKey() == null) throw new IllegalStateException("Refresh token ID was not generated");
    return keys.getKey().longValue();
  }

  public Optional<RefreshRecord> lockRefreshToken(String hash) {
    return jdbc.query(
            "SELECT id,user_id,issued_at,expires_at,revoked_at,replaced_by FROM refresh_tokens WHERE token_hash=? FOR UPDATE",
            (rs, row) ->
                new RefreshRecord(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getObject(3, java.time.OffsetDateTime.class).toInstant(),
                    rs.getObject(4, java.time.OffsetDateTime.class).toInstant(),
                    instant(rs, 5),
                    rs.getObject(6, Long.class)),
            hash)
        .stream()
        .findFirst();
  }

  public void replaceRefreshToken(long oldId, long replacementId, Instant revokedAt) {
    jdbc.update(
        "UPDATE refresh_tokens SET revoked_at=?,replaced_by=? WHERE id=? AND revoked_at IS NULL AND replaced_by IS NULL",
        revokedAt,
        replacementId,
        oldId);
  }

  public void revokeRefreshToken(String hash, Instant revokedAt) {
    jdbc.update(
        "UPDATE refresh_tokens SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
        revokedAt,
        hash);
  }

  public void revokeUserRefreshTokens(long userId, Instant revokedAt) {
    jdbc.update(
        "UPDATE refresh_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
        revokedAt,
        userId);
  }

  private Grants grants(List<RoleRecord> roleRecords) {
    if (roleRecords.isEmpty()) return new Grants(false, Set.of(), Map.of(), Set.of());
    List<Long> ids = roleRecords.stream().map(RoleRecord::id).toList();
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    Map<String, Set<SemanticPermission>> permissions = new LinkedHashMap<>();
    jdbc.query(
        "SELECT domain,permission FROM role_domain_grants WHERE role_id IN (" + placeholders + ")",
        rs -> {
          permissions
              .computeIfAbsent(rs.getString(1), ignored -> new LinkedHashSet<>())
              .add(SemanticPermission.valueOf(rs.getString(2)));
        },
        ids.toArray());
    Set<SemanticCapability> capabilities = new LinkedHashSet<>();
    jdbc.query(
        "SELECT capability FROM role_capabilities WHERE role_id IN (" + placeholders + ")",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> capabilities.add(SemanticCapability.valueOf(rs.getString(1))),
        ids.toArray());
    return new Grants(
        roleRecords.stream().anyMatch(RoleRecord::admin),
        roleRecords.stream()
            .map(RoleRecord::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
        permissions,
        capabilities);
  }

  private UserRecord user(ResultSet rs, int row) throws SQLException {
    return new UserRecord(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getString("provider"),
        rs.getString("display_name"),
        rs.getString("email"),
        rs.getBoolean("enabled"),
        rs.getBoolean("must_change_password"));
  }

  private Instant instant(ResultSet rs, int column) throws SQLException {
    java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record UserRecord(
      long id,
      String username,
      String passwordHash,
      String provider,
      String displayName,
      String email,
      boolean enabled,
      boolean mustChangePassword) {}

  public record RoleRecord(long id, String name, boolean admin) {}

  public record ServiceAccountRecord(long id, String name, String apiKeyHash, RoleRecord role) {}

  public record RefreshRecord(
      long id,
      long userId,
      Instant issuedAt,
      Instant expiresAt,
      Instant revokedAt,
      Long replacedBy) {}

  private record Grants(
      boolean admin,
      Set<String> roles,
      Map<String, Set<SemanticPermission>> permissions,
      Set<SemanticCapability> capabilities) {}
}
