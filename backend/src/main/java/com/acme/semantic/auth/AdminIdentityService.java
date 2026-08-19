package com.acme.semantic.auth;

import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticCapability;
import com.acme.semantic.core.SemanticPermission;
import com.acme.semantic.core.SemanticPrincipal;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminIdentityService {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,255}");
  private static final Pattern DOMAIN = Pattern.compile("\\*|[A-Za-z_][A-Za-z0-9_]*");
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final ApiKeyHasher apiKeys;
  private final SemanticAccessPolicy policy;
  private final AdminAuditLogger audit;
  private final SecureRandom random = new SecureRandom();

  public AdminIdentityService(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      ApiKeyHasher apiKeys,
      SemanticAccessPolicy policy,
      AdminAuditLogger audit) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.apiKeys = apiKeys;
    this.policy = policy;
    this.audit = audit;
  }

  public List<UserView> users(SemanticPrincipal actor) {
    policy.requireAdmin(actor);
    return jdbc.query(
        "SELECT id,username,provider,display_name,email,enabled,must_change_password,created_at,updated_at FROM users ORDER BY username",
        this::userView);
  }

  public UserView user(SemanticPrincipal actor, long id) {
    policy.requireAdmin(actor);
    return findUser(id);
  }

  @Transactional
  public UserView createUser(
      SemanticPrincipal actor,
      String username,
      String password,
      String displayName,
      String email) {
    return mutation(
        actor,
        "user.create",
        "user:" + username,
        () -> {
          String normalized = name(username, "username").toLowerCase(Locale.ROOT);
          password(password);
          Instant now = Instant.now();
          try {
            long id =
                insert(
                    "INSERT INTO users(username,password_hash,provider,display_name,email,enabled,must_change_password,created_at,updated_at) VALUES (?,?,?,?,?,TRUE,FALSE,?,?)",
                    normalized,
                    passwords.encode(password),
                    "local",
                    text(displayName, normalized),
                    nullable(email),
                    now,
                    now);
            return findUser(id);
          } catch (DataIntegrityViolationException exception) {
            throw new AdminConflictException("A user with that username already exists");
          }
        });
  }

  @Transactional
  public UserView updateUser(
      SemanticPrincipal actor,
      long id,
      String displayName,
      String email,
      boolean enabled) {
    return mutation(
        actor,
        "user.update",
        "user:" + id,
        () -> {
          lockAdministration();
          findUser(id);
          if (!enabled && isEnabledAdminUser(id) && enabledAdminUsersExcluding(id) == 0)
            lockout("Cannot disable the last enabled administrator");
          jdbc.update(
              "UPDATE users SET display_name=?,email=?,enabled=?,updated_at=? WHERE id=?",
              text(displayName, null),
              nullable(email),
              enabled,
              Instant.now(),
              id);
          if (!enabled)
            jdbc.update(
                "UPDATE refresh_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
                Instant.now(),
                id);
          return findUser(id);
        });
  }

  @Transactional
  public void deleteUser(SemanticPrincipal actor, long id) {
    mutation(
        actor,
        "user.delete",
        "user:" + id,
        () -> {
          lockAdministration();
          findUser(id);
          if (isEnabledAdminUser(id) && enabledAdminUsersExcluding(id) == 0)
            lockout("Cannot delete the last enabled administrator");
          jdbc.update("DELETE FROM users WHERE id=?", id);
          return null;
        });
  }

  @Transactional
  public UserView setUserRoles(SemanticPrincipal actor, long id, Set<Long> roleIds) {
    return mutation(
        actor,
        "user.roles.replace",
        "user:" + id,
        () -> {
          lockAdministration();
          findUser(id);
          Set<Long> roles = roleIds == null ? Set.of() : Set.copyOf(roleIds);
          requireRoles(roles);
          if (userEnabled(id) && enabledAdminUsersExcluding(id) == 0 && !containsAdminRole(roles))
            lockout("Cannot remove the admin role from the last enabled administrator");
          jdbc.update("DELETE FROM user_roles WHERE user_id=?", id);
          roles.forEach(
              roleId -> jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", id, roleId));
          return findUser(id);
        });
  }

  @Transactional
  public void resetPassword(SemanticPrincipal actor, long id, String newPassword) {
    mutation(
        actor,
        "user.password.reset",
        "user:" + id,
        () -> {
          password(newPassword);
          UserView user = findUser(id);
          if (!"local".equals(user.provider()))
            throw new IllegalArgumentException("Only local user passwords can be reset");
          Instant now = Instant.now();
          jdbc.update(
              "UPDATE users SET password_hash=?,must_change_password=TRUE,updated_at=? WHERE id=?",
              passwords.encode(newPassword),
              now,
              id);
          jdbc.update(
              "UPDATE refresh_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now, id);
          return null;
        });
  }

  public List<RoleView> roles(SemanticPrincipal actor) {
    policy.requireAdmin(actor);
    return jdbc.queryForList("SELECT id FROM roles ORDER BY name", Long.class).stream()
        .map(this::findRole)
        .toList();
  }

  public RoleView role(SemanticPrincipal actor, long id) {
    policy.requireAdmin(actor);
    return findRole(id);
  }

  @Transactional
  public RoleView createRole(
      SemanticPrincipal actor, String roleName, String description, boolean admin) {
    return mutation(
        actor,
        "role.create",
        "role:" + roleName,
        () -> {
          String normalized = name(roleName, "role name");
          try {
            long id =
                insert(
                    "INSERT INTO roles(name,description,is_admin) VALUES (?,?,?)",
                    normalized,
                    nullable(description),
                    admin);
            return findRole(id);
          } catch (DataIntegrityViolationException exception) {
            throw new AdminConflictException("A role with that name already exists");
          }
        });
  }

  @Transactional
  public RoleView updateRole(
      SemanticPrincipal actor, long id, String description, boolean admin) {
    return mutation(
        actor,
        "role.update",
        "role:" + id,
        () -> {
          lockAdministration();
          RoleView role = findRole(id);
          if (role.admin() && !admin && enabledAdminsWithoutRole(id) == 0)
            lockout("Cannot clear admin status from the last role serving enabled administrators");
          jdbc.update(
              "UPDATE roles SET description=?,is_admin=? WHERE id=?",
              nullable(description),
              admin,
              id);
          return findRole(id);
        });
  }

  @Transactional
  public void deleteRole(SemanticPrincipal actor, long id) {
    mutation(
        actor,
        "role.delete",
        "role:" + id,
        () -> {
          lockAdministration();
          RoleView role = findRole(id);
          if (role.admin() && enabledAdminsWithoutRole(id) == 0)
            lockout("Cannot delete the last role serving enabled administrators");
          Long accounts =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM service_accounts WHERE role_id=?", Long.class, id);
          if (accounts != null && accounts > 0)
            throw new AdminConflictException(
                "Cannot delete a role that is assigned to a service account");
          jdbc.update("DELETE FROM roles WHERE id=?", id);
          return null;
        });
  }

  @Transactional
  public RoleView setRoleGrants(
      SemanticPrincipal actor, long id, List<DomainGrant> grants) {
    return mutation(
        actor,
        "role.grants.replace",
        "role:" + id,
        () -> {
          findRole(id);
          List<DomainGrant> values = grants == null ? List.of() : List.copyOf(grants);
          for (DomainGrant grant : values) {
            if (grant == null || grant.domain() == null || !DOMAIN.matcher(grant.domain()).matches())
              throw new IllegalArgumentException("Invalid grant domain");
            if (grant.permissions() == null || grant.permissions().isEmpty())
              throw new IllegalArgumentException("Each domain grant needs at least one permission");
          }
          jdbc.update("DELETE FROM role_domain_grants WHERE role_id=?", id);
          for (DomainGrant grant : values)
            for (SemanticPermission permission : grant.permissions())
              jdbc.update(
                  "INSERT INTO role_domain_grants(role_id,domain,permission) VALUES (?,?,?)",
                  id,
                  grant.domain(),
                  permission.name());
          return findRole(id);
        });
  }

  @Transactional
  public RoleView setRoleCapabilities(
      SemanticPrincipal actor, long id, Set<SemanticCapability> capabilities) {
    return mutation(
        actor,
        "role.capabilities.replace",
        "role:" + id,
        () -> {
          findRole(id);
          Set<SemanticCapability> values =
              capabilities == null ? Set.of() : Set.copyOf(capabilities);
          jdbc.update("DELETE FROM role_capabilities WHERE role_id=?", id);
          values.forEach(
              capability ->
                  jdbc.update(
                      "INSERT INTO role_capabilities(role_id,capability) VALUES (?,?)",
                      id,
                      capability.name()));
          return findRole(id);
        });
  }

  public List<ServiceAccountView> serviceAccounts(SemanticPrincipal actor) {
    policy.requireAdmin(actor);
    return jdbc.query(
        "SELECT sa.id,sa.name,sa.enabled,sa.requires_rotation,r.id,r.name,r.is_admin FROM service_accounts sa JOIN roles r ON r.id=sa.role_id ORDER BY sa.name",
        this::serviceAccountView);
  }

  public ServiceAccountView serviceAccount(SemanticPrincipal actor, long id) {
    policy.requireAdmin(actor);
    return findServiceAccount(id);
  }

  @Transactional
  public ServiceAccountSecret createServiceAccount(
      SemanticPrincipal actor, String accountName, long roleId) {
    return mutation(
        actor,
        "service-account.create",
        "service-account:" + accountName,
        () -> {
          String normalized = name(accountName, "service account name");
          findRole(roleId);
          String key = secret();
          try {
            long id =
                insert(
                    "INSERT INTO service_accounts(name,api_key_hash,role_id,enabled,requires_rotation) VALUES (?,?,?,TRUE,FALSE)",
                    normalized,
                    apiKeys.hash(key),
                    roleId);
            return new ServiceAccountSecret(findServiceAccount(id), key);
          } catch (DataIntegrityViolationException exception) {
            throw new AdminConflictException("A service account with that name already exists");
          }
        });
  }

  @Transactional
  public ServiceAccountView updateServiceAccount(
      SemanticPrincipal actor, long id, long roleId, boolean enabled) {
    return mutation(
        actor,
        "service-account.update",
        "service-account:" + id,
        () -> {
          findServiceAccount(id);
          findRole(roleId);
          jdbc.update(
              "UPDATE service_accounts SET role_id=?,enabled=? WHERE id=?", roleId, enabled, id);
          return findServiceAccount(id);
        });
  }

  @Transactional
  public ServiceAccountSecret rotateServiceAccount(SemanticPrincipal actor, long id) {
    return mutation(
        actor,
        "service-account.rotate",
        "service-account:" + id,
        () -> {
          findServiceAccount(id);
          String key = secret();
          jdbc.update(
              "UPDATE service_accounts SET api_key_hash=?,requires_rotation=FALSE WHERE id=?",
              apiKeys.hash(key),
              id);
          return new ServiceAccountSecret(findServiceAccount(id), key);
        });
  }

  @Transactional
  public void deleteServiceAccount(SemanticPrincipal actor, long id) {
    mutation(
        actor,
        "service-account.delete",
        "service-account:" + id,
        () -> {
          findServiceAccount(id);
          jdbc.update("DELETE FROM service_accounts WHERE id=?", id);
          return null;
        });
  }

  private UserView findUser(long id) {
    return jdbc.query(
            "SELECT id,username,provider,display_name,email,enabled,must_change_password,created_at,updated_at FROM users WHERE id=?",
            this::userView,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AdminNotFoundException("User was not found"));
  }

  private UserView userView(ResultSet rs, int row) throws SQLException {
    long id = rs.getLong("id");
    return new UserView(
        id,
        rs.getString("username"),
        rs.getString("provider"),
        rs.getString("display_name"),
        rs.getString("email"),
        rs.getBoolean("enabled"),
        rs.getBoolean("must_change_password"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        jdbc.query(
            "SELECT r.id,r.name,r.is_admin FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.name",
            (roles, ignored) ->
                new RoleSummary(roles.getLong(1), roles.getString(2), roles.getBoolean(3)),
            id));
  }

  private RoleView findRole(long id) {
    return jdbc.query(
            "SELECT id,name,description,is_admin FROM roles WHERE id=?",
            (rs, row) ->
                new RoleView(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    grants(id),
                    capabilities(id)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AdminNotFoundException("Role was not found"));
  }

  private List<DomainGrant> grants(long roleId) {
    Map<String, Set<SemanticPermission>> byDomain = new LinkedHashMap<>();
    jdbc.query(
        "SELECT domain,permission FROM role_domain_grants WHERE role_id=? ORDER BY domain,permission",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                byDomain
                    .computeIfAbsent(rs.getString(1), ignored -> new LinkedHashSet<>())
                    .add(SemanticPermission.valueOf(rs.getString(2))),
        roleId);
    List<DomainGrant> result = new ArrayList<>();
    byDomain.forEach((domain, permissions) -> result.add(new DomainGrant(domain, permissions)));
    return List.copyOf(result);
  }

  private Set<SemanticCapability> capabilities(long roleId) {
    return new LinkedHashSet<>(
        jdbc.queryForList(
                "SELECT capability FROM role_capabilities WHERE role_id=? ORDER BY capability",
                String.class,
                roleId)
            .stream()
            .map(SemanticCapability::valueOf)
            .toList());
  }

  private ServiceAccountView findServiceAccount(long id) {
    return jdbc.query(
            "SELECT sa.id,sa.name,sa.enabled,sa.requires_rotation,r.id,r.name,r.is_admin FROM service_accounts sa JOIN roles r ON r.id=sa.role_id WHERE sa.id=?",
            this::serviceAccountView,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AdminNotFoundException("Service account was not found"));
  }

  private ServiceAccountView serviceAccountView(ResultSet rs, int row) throws SQLException {
    return new ServiceAccountView(
        rs.getLong(1),
        rs.getString(2),
        rs.getBoolean(3),
        rs.getBoolean(4),
        new RoleSummary(rs.getLong(5), rs.getString(6), rs.getBoolean(7)));
  }

  private void lockAdministration() {
    jdbc.queryForList("SELECT id FROM users FOR UPDATE", Long.class);
    jdbc.queryForList("SELECT id FROM roles FOR UPDATE", Long.class);
    jdbc.queryForList("SELECT user_id FROM user_roles FOR UPDATE", Long.class);
  }

  private boolean isEnabledAdminUser(long id) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT u.id) FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.id=? AND u.enabled=TRUE AND r.is_admin=TRUE",
            Long.class,
            id);
    return count != null && count > 0;
  }

  private long enabledAdminUsersExcluding(long userId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT u.id) FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.enabled=TRUE AND r.is_admin=TRUE AND u.id<>?",
            Long.class,
            userId);
    return count == null ? 0 : count;
  }

  private long enabledAdminsWithoutRole(long roleId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT u.id) FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.enabled=TRUE AND r.is_admin=TRUE AND r.id<>?",
            Long.class,
            roleId);
    return count == null ? 0 : count;
  }

  private boolean userEnabled(long id) {
    Boolean enabled = jdbc.queryForObject("SELECT enabled FROM users WHERE id=?", Boolean.class, id);
    return Boolean.TRUE.equals(enabled);
  }

  private boolean containsAdminRole(Set<Long> roleIds) {
    if (roleIds.isEmpty()) return false;
    String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM roles WHERE is_admin=TRUE AND id IN (" + placeholders + ")",
            Long.class,
            roleIds.toArray());
    return count != null && count > 0;
  }

  private void requireRoles(Set<Long> roleIds) {
    if (roleIds.isEmpty()) return;
    String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM roles WHERE id IN (" + placeholders + ")",
            Long.class,
            roleIds.toArray());
    if (count == null || count != roleIds.size()) throw new AdminNotFoundException("Role was not found");
  }

  private <T> T mutation(
      SemanticPrincipal actor, String action, String target, Supplier<T> operation) {
    try {
      policy.requireAdmin(actor);
      T result = operation.get();
      audit.mutation(actor, action, target, "success", null);
      return result;
    } catch (RuntimeException exception) {
      audit.mutation(actor, action, target, "failure", exception.getMessage());
      throw exception;
    }
  }

  private void lockout(String message) {
    throw new AdminConflictException(message);
  }

  private long insert(String sql, Object... arguments) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int index = 0; index < arguments.length; index++)
            statement.setObject(index + 1, arguments[index]);
          return statement;
        },
        keys);
    if (keys.getKey() == null) throw new IllegalStateException("Identity ID was not generated");
    return keys.getKey().longValue();
  }

  private String name(String value, String label) {
    if (value == null || !NAME.matcher(value.trim()).matches())
      throw new IllegalArgumentException("Invalid " + label);
    return value.trim();
  }

  private void password(String value) {
    if (value == null || value.length() < 8)
      throw new IllegalArgumentException("Password must be at least 8 characters");
  }

  private String nullable(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String text(String value, String fallback) {
    String normalized = nullable(value);
    return normalized == null ? fallback : normalized;
  }

  private String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public record RoleSummary(long id, String name, boolean admin) {}

  public record UserView(
      long id,
      String username,
      String provider,
      String displayName,
      String email,
      boolean enabled,
      boolean mustChangePassword,
      Instant createdAt,
      Instant updatedAt,
      List<RoleSummary> roles) {}

  public record DomainGrant(String domain, Set<SemanticPermission> permissions) {
    public DomainGrant {
      permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
  }

  public record RoleView(
      long id,
      String name,
      String description,
      boolean admin,
      List<DomainGrant> grants,
      Set<SemanticCapability> capabilities) {}

  public record ServiceAccountView(
      long id, String name, boolean enabled, boolean requiresRotation, RoleSummary role) {}

  public record ServiceAccountSecret(ServiceAccountView serviceAccount, String apiKey) {}
}
