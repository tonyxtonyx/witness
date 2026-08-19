package com.acme.semantic.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.config.WitnessAuthProperties;
import org.junit.jupiter.api.Test;

class JwtSecretProviderTest {
  @Test
  void requiresThirtyTwoBytesUnlessExistingInsecureModeIsExplicit() {
    assertThatThrownBy(() -> provider("short", false).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WITNESS_JWT_SECRET");
    assertThatCode(() -> provider("short", true).afterPropertiesSet())
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                provider("0123456789abcdef0123456789abcdef", false)
                    .afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  private JwtSecretProvider provider(String secret, boolean insecure) {
    WitnessAuthProperties auth = new WitnessAuthProperties(secret, true, 60, 30);
    SemanticProperties semantic =
        new SemanticProperties("semantic-model", "api-key", insecure, null, null, null, null);
    return new JwtSecretProvider(auth, semantic);
  }
}
