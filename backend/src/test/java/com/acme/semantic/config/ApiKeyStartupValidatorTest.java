package com.acme.semantic.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApiKeyStartupValidatorTest {
  @Test
  void rejectsMissingAndPublishedKeysUnlessDemoOptOutIsExplicit() {
    assertThatThrownBy(() -> validator("", false).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REST_API_KEY");
    assertThatThrownBy(() -> validator("dev-secret", false).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REST_API_KEY");
    assertThatCode(() -> validator("dev-secret", true).afterPropertiesSet())
        .doesNotThrowAnyException();
    assertThatCode(() -> validator("deployment-secret", false).afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  private ApiKeyStartupValidator validator(String key, boolean allowInsecure) {
    SemanticProperties properties =
        new SemanticProperties(
            "semantic-model", key, allowInsecure, null, null, null, null);
    return new ApiKeyStartupValidator(properties);
  }
}
