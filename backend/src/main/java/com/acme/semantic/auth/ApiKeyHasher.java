package com.acme.semantic.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyHasher {
  private final byte[] pepper;

  public ApiKeyHasher(JwtSecretProvider secrets) {
    this.pepper = secrets.secret();
  }

  public String hash(String apiKey) {
    if (apiKey == null) throw new IllegalArgumentException("API key is required");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
    }
  }

  public boolean matches(String computedHash, String storedHash) {
    if (computedHash == null || storedHash == null) return false;
    try {
      return MessageDigest.isEqual(
          HexFormat.of().parseHex(computedHash), HexFormat.of().parseHex(storedHash));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
