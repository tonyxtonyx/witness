package com.acme.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "witness.auth")
public record WitnessAuthProperties(
    String jwtSecret,
    boolean allowDefaultAdmin,
    int accessTokenMinutes,
    int refreshTokenDays) {}
