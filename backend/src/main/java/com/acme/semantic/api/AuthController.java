package com.acme.semantic.api;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.config.WitnessAuthProperties;
import com.acme.semantic.core.SemanticPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  static final String REFRESH_COOKIE = "witness_refresh";
  private final AuthenticationService authentication;
  private final WitnessAuthProperties properties;

  public AuthController(
      AuthenticationService authentication, WitnessAuthProperties properties) {
    this.authentication = authentication;
    this.properties = properties;
  }

  @PostMapping("/login")
  public AuthenticationService.TokenResponse login(
      @RequestBody LoginRequest request,
      HttpServletRequest servletRequest,
      HttpServletResponse response) {
    AuthenticationService.TokenResponse tokens =
        authentication.login(request.username(), request.password());
    setRefreshCookie(response, servletRequest, tokens.refreshToken());
    return tokens;
  }

  @PostMapping("/refresh")
  public AuthenticationService.TokenResponse refresh(
      @RequestBody(required = false) RefreshRequest request,
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
      HttpServletRequest servletRequest,
      HttpServletResponse response) {
    AuthenticationService.TokenResponse tokens =
        authentication.refresh(token(request, cookie));
    setRefreshCookie(response, servletRequest, tokens.refreshToken());
    return tokens;
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @RequestBody(required = false) RefreshRequest request,
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
      HttpServletRequest servletRequest,
      HttpServletResponse response) {
    try {
      authentication.logout(token(request, cookie));
    } finally {
      clearRefreshCookie(response, servletRequest);
    }
  }

  @GetMapping("/me")
  public AuthenticationService.UserView me(HttpServletRequest request) {
    return authentication.me(ApiSecurityFilter.principal(request));
  }

  @PostMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(@RequestBody PasswordRequest request, HttpServletRequest servletRequest) {
    SemanticPrincipal principal = ApiSecurityFilter.principal(servletRequest);
    authentication.changePassword(principal, request.currentPassword(), request.newPassword());
  }

  public record LoginRequest(String username, String password) {}

  public record RefreshRequest(String refreshToken) {}

  public record PasswordRequest(String currentPassword, String newPassword) {}

  private String token(RefreshRequest request, String cookie) {
    return request != null
            && request.refreshToken() != null
            && !request.refreshToken().isBlank()
        ? request.refreshToken()
        : cookie;
  }

  private void setRefreshCookie(
      HttpServletResponse response, HttpServletRequest request, String token) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        cookie(request, token, Duration.ofDays(Math.max(1, properties.refreshTokenDays())))
            .toString());
  }

  private void clearRefreshCookie(
      HttpServletResponse response, HttpServletRequest request) {
    response.addHeader(
        HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
  }

  private ResponseCookie cookie(
      HttpServletRequest request, String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(
            request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")))
        .sameSite("Strict")
        .path("/api/v1/auth")
        .maxAge(maxAge)
        .build();
  }
}
