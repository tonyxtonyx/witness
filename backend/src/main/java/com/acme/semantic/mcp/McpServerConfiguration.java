package com.acme.semantic.mcp;

import com.acme.semantic.api.ApiSecurityFilter;
import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticPrincipal;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "semantic.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfiguration {
  @Bean
  McpJsonMapper mcpJsonMapper() {
    return McpJsonDefaults.getMapper();
  }

  @Bean
  HttpServletStatelessServerTransport mcpTransport(
      SemanticProperties properties, McpJsonMapper mapper, AuthenticationService authentication) {
    SemanticProperties.Mcp config =
        properties.mcp() == null ? SemanticProperties.Mcp.defaults() : properties.mcp();
    McpTransportSecurity security = new McpTransportSecurity(authentication);
    return HttpServletStatelessServerTransport.builder()
        .jsonMapper(mapper)
        .messageEndpoint(config.endpoint())
        .securityValidator(security)
        .contextExtractor(
            request -> {
              Map<String, Object> values = new LinkedHashMap<>();
              Object principal = request.getAttribute(ApiSecurityFilter.PRINCIPAL_ATTRIBUTE);
              Object trace = request.getAttribute(ApiSecurityFilter.CORRELATION_ATTRIBUTE);
              if (!(principal instanceof SemanticPrincipal))
                principal =
                    security
                        .authenticate(
                            request.getHeader("Authorization"), request.getHeader("X-API-Key"))
                        .orElse(null);
              if (trace == null) trace = UUID.randomUUID().toString();
              if (principal != null) {
                values.put(McpSemanticTools.PRINCIPAL_CONTEXT_KEY, principal);
              }
              if (trace != null) values.put(McpSemanticTools.TRACE_CONTEXT_KEY, trace.toString());
              return McpTransportContext.create(Map.copyOf(values));
            })
        .build();
  }

  @Bean(destroyMethod = "close")
  McpStatelessSyncServer mcpServer(
      HttpServletStatelessServerTransport transport,
      McpSemanticTools tools,
      SemanticProperties properties) {
    int timeout =
        properties.trino() == null ? 35 : Math.max(5, properties.trino().timeoutSeconds() + 5);
    return McpServer.sync(transport)
        .serverInfo("witness-semantic-mcp", "0.1.0")
        .instructions(
            "Discover governed semantic IDs before querying. Use only canonical semantic queries; raw SQL and caller-supplied identities are not accepted.")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .strictToolNameValidation(true)
        .validateToolInputs(false)
        .requestTimeout(Duration.ofSeconds(timeout))
        .tools(tools.specifications())
        .build();
  }

  @Bean
  ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
      HttpServletStatelessServerTransport transport, SemanticProperties properties) {
    SemanticProperties.Mcp config =
        properties.mcp() == null ? SemanticProperties.Mcp.defaults() : properties.mcp();
    ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
        new ServletRegistrationBean<>(transport, config.endpoint());
    registration.setName("witnessMcpServlet");
    registration.setLoadOnStartup(1);
    return registration;
  }
}
