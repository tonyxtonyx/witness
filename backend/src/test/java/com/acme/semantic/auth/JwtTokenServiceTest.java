package com.acme.semantic.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {
  private static final byte[] SECRET =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-19T08:00:00Z"), ZoneOffset.UTC);
  private final JwtTokenService tokens = new JwtTokenService(SECRET, Duration.ofHours(1), CLOCK);

  @Test
  void acceptsValidTokenAndRejectsExpiredTamperedWrongSignatureAndAlgNone() {
    String valid = tokens.issueAccessToken(42);
    assertThat(tokens.verifyAccessToken(valid)).isEqualTo(42);

    String expired = tokens.issueAccessToken(42, Duration.ofSeconds(-1));
    assertInvalid(expired);

    String[] pieces = valid.split("\\.");
    byte[] payload = Base64.getUrlDecoder().decode(pieces[1]);
    payload[payload.length / 2] ^= 1;
    assertInvalid(
        pieces[0]
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
            + "."
            + pieces[2]);

    JwtTokenService other =
        new JwtTokenService(
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8),
            Duration.ofHours(1),
            CLOCK);
    assertInvalid(other.issueAccessToken(42));

    String noneHeader =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    assertInvalid(noneHeader + "." + pieces[1] + ".");
  }

  private void assertInvalid(String token) {
    assertThatThrownBy(() -> tokens.verifyAccessToken(token))
        .isInstanceOf(JwtTokenService.InvalidTokenException.class);
  }
}
