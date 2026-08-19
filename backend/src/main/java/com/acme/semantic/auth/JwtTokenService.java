package com.acme.semantic.auth;

import com.acme.semantic.config.WitnessAuthProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
  private static final String ISSUER = "witness";
  private static final String AUDIENCE = "witness-api";
  private final byte[] secret;
  private final Duration accessTtl;
  private final Clock clock;

  @Autowired
  public JwtTokenService(JwtSecretProvider secret, WitnessAuthProperties properties) {
    this(secret.secret(), Duration.ofMinutes(Math.max(1, properties.accessTokenMinutes())), Clock.systemUTC());
  }

  JwtTokenService(byte[] secret, Duration accessTtl, Clock clock) {
    this.secret = secret.clone();
    this.accessTtl = accessTtl;
    this.clock = clock;
  }

  public String issueAccessToken(long userId) {
    return issueAccessToken(userId, accessTtl);
  }

  String issueAccessToken(long userId, Duration ttl) {
    try {
      Instant now = clock.instant();
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(ISSUER)
              .audience(AUDIENCE)
              .subject(Long.toString(userId))
              .jwtID(UUID.randomUUID().toString())
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plus(ttl)))
              .claim("token_use", "access")
              .build();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (Exception exception) {
      throw new IllegalStateException("Could not issue access token", exception);
    }
  }

  public long verifyAccessToken(String token) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) throw invalid();
      if (!jwt.verify(new MACVerifier(secret))) throw invalid();
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      Instant now = clock.instant();
      if (!ISSUER.equals(claims.getIssuer())
          || !claims.getAudience().contains(AUDIENCE)
          || !"access".equals(claims.getStringClaim("token_use"))
          || claims.getExpirationTime() == null
          || !claims.getExpirationTime().toInstant().isAfter(now)) throw invalid();
      return Long.parseLong(claims.getSubject());
    } catch (InvalidTokenException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid();
    }
  }

  public long expiresInSeconds() {
    return accessTtl.toSeconds();
  }

  private InvalidTokenException invalid() {
    return new InvalidTokenException();
  }

  public static final class InvalidTokenException extends RuntimeException {
    private InvalidTokenException() {
      super("Invalid access token");
    }
  }
}
