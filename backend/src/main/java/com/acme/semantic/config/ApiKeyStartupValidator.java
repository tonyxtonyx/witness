package com.acme.semantic.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyStartupValidator implements InitializingBean {
  private static final Logger log = LoggerFactory.getLogger(ApiKeyStartupValidator.class);
  private final SemanticProperties properties;

  public ApiKeyStartupValidator(SemanticProperties properties) {
    this.properties = properties;
  }

  @Override
  public void afterPropertiesSet() {
    String key = properties.apiKey();
    if (key != null && !key.isBlank() && !key.equals("dev-secret")) return;
    String message =
        "REST API key is insecure; set the REST_API_KEY environment variable to a strong secret";
    if (!properties.allowInsecureApiKey()) throw new IllegalStateException(message);
    log.warn("SECURITY WARNING: {}. Insecure API-key mode is explicitly enabled", message);
  }
}
