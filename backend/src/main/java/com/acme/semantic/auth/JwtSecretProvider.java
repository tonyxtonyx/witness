package com.acme.semantic.auth;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.config.WitnessAuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class JwtSecretProvider implements InitializingBean {
  private static final Logger log = LoggerFactory.getLogger(JwtSecretProvider.class);
  private final WitnessAuthProperties auth;
  private final SemanticProperties semantic;
  private byte[] secret;

  public JwtSecretProvider(WitnessAuthProperties auth, SemanticProperties semantic) {
    this.auth = auth;
    this.semantic = semantic;
  }

  @Override
  public void afterPropertiesSet() {
    byte[] configured =
        auth.jwtSecret() == null
            ? new byte[0]
            : auth.jwtSecret().getBytes(StandardCharsets.UTF_8);
    if (configured.length >= 32) {
      secret = configured.clone();
      return;
    }
    String message =
        "WITNESS_JWT_SECRET must contain at least 32 UTF-8 bytes; blank or short secrets are unsafe";
    if (!semantic.allowInsecureApiKey()) throw new IllegalStateException(message);
    secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    log.warn(
        "SECURITY WARNING: {}. Insecure mode generated a per-boot JWT and API-key hashing secret; all access tokens and existing service-account keys will become invalid when the application restarts",
        message);
  }

  byte[] secret() {
    return secret.clone();
  }
}
