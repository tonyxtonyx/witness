package com.acme.semantic.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:api-key-scale;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "semantic.api-key=api-key-scale-bootstrap",
      "semantic.pgwire.enabled=false",
      "semantic.mcp.enabled=false",
      "semantic.gitlab.enabled=false",
      "semantic.model-path=semantic-model",
      "witness.auth.jwt-secret=0123456789abcdef0123456789abcdef",
      "witness.auth.allow-default-admin=true"
    })
class ApiKeyLookupIntegrationTest {
  @Autowired AuthenticationService authentication;
  @Autowired JdbcTemplate jdbc;
  @SpyBean ApiKeyHasher apiKeys;

  private long role;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM service_accounts");
    role = jdbc.queryForObject("SELECT id FROM roles WHERE name='admin'", Long.class);
  }

  @Test
  void hashComputationCountDoesNotScaleWithEnabledServiceAccounts() {
    insertAccounts(1);
    clearInvocations(apiKeys);
    assertThat(authentication.authenticateApiKey("not-a-key")).isEmpty();
    verify(apiKeys, times(1)).hash("not-a-key");

    jdbc.update("DELETE FROM service_accounts");
    insertAccounts(25);
    clearInvocations(apiKeys);
    assertThat(authentication.authenticateApiKey("not-a-key")).isEmpty();
    verify(apiKeys, times(1)).hash("not-a-key");

    clearInvocations(apiKeys);
    assertThat(authentication.authenticateApiKey("scale-key-24")).isPresent();
    verify(apiKeys, times(1)).hash("scale-key-24");
  }

  private void insertAccounts(int count) {
    for (int index = 0; index < count; index++) {
      String key = "scale-key-" + index;
      jdbc.update(
          "INSERT INTO service_accounts(name,api_key_hash,role_id,enabled,requires_rotation) VALUES (?,?,?,TRUE,FALSE)",
          "scale-account-" + index,
          apiKeys.hash(key),
          role);
    }
  }
}
