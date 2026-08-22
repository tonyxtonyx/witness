package com.acme.semantic.auth;

import com.acme.semantic.config.WitnessAuthProperties;
import com.acme.semantic.core.SemanticCapability;
import com.acme.semantic.core.SemanticPermission;
import com.acme.semantic.core.SemanticPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
  private final IdentityRepository identities;
  private final JwtTokenService jwt;
  private final PasswordEncoder passwords;
  private final ApiKeyHasher apiKeys;
  private final WitnessAuthProperties properties;
  private final SecureRandom random = new SecureRandom();
  private final String dummyPasswordHash;

  public AuthenticationService(
      IdentityRepository identities,
      JwtTokenService jwt,
      PasswordEncoder passwords,
      ApiKeyHasher apiKeys,
      WitnessAuthProperties properties) {
    this.identities = identities;
    this.jwt = jwt;
    this.passwords = passwords;
    this.apiKeys = apiKeys;
    this.properties = properties;
    this.dummyPasswordHash = passwords.encode("witness-dummy-password-never-valid");
  }

  @Transactional
  public TokenResponse login(String username, String password) {
    String suppliedUsername = username == null ? "" : username;
    String suppliedPassword = password == null ? "" : password;
    Optional<IdentityRepository.UserRecord> found = identities.findUserByUsername(suppliedUsername);
    IdentityRepository.UserRecord user = found.orElse(null);
    String hash = user == null || user.passwordHash() == null ? dummyPasswordHash : user.passwordHash();
    boolean matches = passwords.matches(suppliedPassword, hash);
    if (user == null
        || !matches
        || !user.enabled()
        || !"local".equals(user.provider())
        || user.passwordHash() == null) throw new InvalidCredentialsException();
    return tokens(user, newRefreshToken(user.id()));
  }

  public Optional<SemanticPrincipal> authenticatePassword(String username, String password) {
    String suppliedUsername = username == null ? "" : username;
    String suppliedPassword = password == null ? "" : password;
    IdentityRepository.UserRecord user =
        identities.findUserByUsername(suppliedUsername).orElse(null);
    String hash = user == null || user.passwordHash() == null ? dummyPasswordHash : user.passwordHash();
    boolean matches = passwords.matches(suppliedPassword, hash);
    if (user == null
        || !matches
        || !user.enabled()
        || !"local".equals(user.provider())
        || user.passwordHash() == null) return Optional.empty();
    return identities.resolveUser(user.id());
  }

  public Optional<SemanticPrincipal> authenticateAccessToken(String token) {
    try {
      return identities.resolveUser(jwt.verifyAccessToken(token));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  public Optional<SemanticPrincipal> authenticateApiKey(String key) {
    if (key == null || key.isBlank()) return Optional.empty();
    String hash = apiKeys.hash(key);
    return identities
        .findEnabledServiceAccountByHash(hash)
        .filter(account -> apiKeys.matches(hash, account.apiKeyHash()))
        .map(identities::resolveServiceAccount);
  }

  public Optional<SemanticPrincipal> refreshPrincipal(SemanticPrincipal principal) {
    if (principal == null || !principal.authenticated()) return Optional.empty();
    try {
      if (principal.serviceAccount() && principal.id().startsWith("service-account:"))
        return identities
            .findEnabledServiceAccountById(
                Long.parseLong(principal.id().substring("service-account:".length())))
            .map(identities::resolveServiceAccount);
      if (!principal.serviceAccount() && principal.id().startsWith("user:"))
        return identities.resolveUser(Long.parseLong(principal.id().substring("user:".length())));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  @Transactional
  public TokenResponse refresh(String refreshToken) {
    String hash = hash(refreshToken);
    IdentityRepository.RefreshRecord current =
        identities.lockRefreshToken(hash).orElseThrow(InvalidRefreshTokenException::new);
    Instant now = Instant.now();
    if (current.revokedAt() != null
        || current.replacedBy() != null
        || !current.expiresAt().isAfter(now)) throw new InvalidRefreshTokenException();
    IdentityRepository.UserRecord user =
        identities
            .findUserById(current.userId())
            .filter(IdentityRepository.UserRecord::enabled)
            .orElseThrow(InvalidRefreshTokenException::new);
    RawRefresh replacement = newRefreshToken(user.id());
    identities.replaceRefreshToken(current.id(), replacement.id(), now);
    return tokens(user, replacement);
  }

  @Transactional
  public void logout(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) return;
    identities.revokeRefreshToken(hash(refreshToken), Instant.now());
  }

  @Transactional
  public void changePassword(SemanticPrincipal principal, String currentPassword, String newPassword) {
    long userId = userId(principal);
    IdentityRepository.UserRecord user =
        identities.findUserById(userId).filter(IdentityRepository.UserRecord::enabled).orElseThrow(InvalidCredentialsException::new);
    boolean matches =
        user.passwordHash() != null
            && passwords.matches(currentPassword == null ? "" : currentPassword, user.passwordHash());
    if (!matches) throw new InvalidCredentialsException();
    if (newPassword == null || newPassword.length() < 8)
      throw new IllegalArgumentException("New password must contain at least 8 characters");
    identities.updatePassword(userId, passwords.encode(newPassword));
    identities.revokeUserRefreshTokens(userId, Instant.now());
  }

  public UserView me(SemanticPrincipal principal) {
    long userId = userId(principal);
    IdentityRepository.UserRecord user =
        identities.findUserById(userId).filter(IdentityRepository.UserRecord::enabled).orElseThrow(InvalidCredentialsException::new);
    return user(user);
  }

  private TokenResponse tokens(IdentityRepository.UserRecord user, RawRefresh refresh) {
    return new TokenResponse(
        jwt.issueAccessToken(user.id()),
        refresh.token(),
        jwt.expiresInSeconds(),
        "Bearer",
        user(user));
  }

  private UserView user(IdentityRepository.UserRecord user) {
    SemanticPrincipal principal = identities.resolveUser(user.id()).orElseThrow(InvalidCredentialsException::new);
    return new UserView(
        user.username(),
        user.displayName(),
        identities.roleNames(user.id()),
        principal.admin(),
        user.mustChangePassword(),
        principal.admin() ? Map.of() : principal.domainPermissions(),
        principal.admin() ? Set.of() : principal.capabilities());
  }

  private RawRefresh newRefreshToken(long userId) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = Instant.now();
    long id =
        identities.insertRefreshToken(
            userId,
            hash(token),
            now,
            now.plus(Math.max(1, properties.refreshTokenDays()), ChronoUnit.DAYS));
    return new RawRefresh(id, token);
  }

  private long userId(SemanticPrincipal principal) {
    if (principal == null
        || !principal.authenticated()
        || principal.serviceAccount()
        || !principal.id().startsWith("user:")) throw new InvalidCredentialsException();
    try {
      return Long.parseLong(principal.id().substring("user:".length()));
    } catch (NumberFormatException exception) {
      throw new InvalidCredentialsException();
    }
  }

  private String hash(String token) {
    if (token == null || token.isBlank()) throw new InvalidRefreshTokenException();
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record TokenResponse(
      String accessToken,
      String refreshToken,
      long expiresIn,
      String tokenType,
      UserView user) {}

  public record UserView(
      String username,
      String displayName,
      List<String> roles,
      boolean admin,
      boolean mustChangePassword,
      Map<String, Set<SemanticPermission>> domainPermissions,
      Set<SemanticCapability> capabilities) {}

  private record RawRefresh(long id, String token) {}

  public static class InvalidCredentialsException extends RuntimeException {}

  public static class InvalidRefreshTokenException extends RuntimeException {}
}
