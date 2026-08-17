package com.acme.semantic.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.AstSemanticSqlCompiler;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticErrorCode;
import com.acme.semantic.core.SemanticException;
import com.acme.semantic.core.SemanticLineageService;
import com.acme.semantic.core.SemanticMetadataService;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.core.SemanticQueryService;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class McpQueryIntegrationTest {
  @Test
  void executesMcpQueryThroughCoreCompilerAndFakeTrino() {
    SemanticModel model = TestModels.demo();
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    QueryExecutor executor = mock(QueryExecutor.class);
    when(executor.execute(any(), anyList()))
        .thenReturn(
            new QueryResult(
                List.of(
                    new QueryResult.Column("country", Types.VARCHAR, "varchar", true),
                    new QueryResult.Column("revenue", Types.DECIMAL, "decimal(18,2)", true)),
                List.of(List.of("FI", new BigDecimal("1289000.20"))),
                "fake-trino-query"));
    SemanticProperties properties =
        new SemanticProperties("semantic-model", "test", null, null, null);
    SemanticQueryService queryService =
        new SemanticQueryService(
            catalog,
            new AllowPolicy(),
            new AstSemanticSqlCompiler(),
            executor,
            properties);
    Logger auditLogger = (Logger) LoggerFactory.getLogger("semantic.mcp.audit");
    ListAppender<ILoggingEvent> auditEvents = new ListAppender<>();
    auditEvents.start();
    auditLogger.addAppender(auditEvents);
    McpSemanticTools adapter =
        new McpSemanticTools(
            catalog,
            mock(SemanticMetadataService.class),
            queryService,
            mock(SemanticLineageService.class),
            new McpAuditLogger(),
            new ObjectMapper().findAndRegisterModules(),
            properties);
    McpStatelessServerFeatures.SyncToolSpecification tool =
        adapter.specifications().stream()
            .filter(candidate -> candidate.tool().name().equals("query_metrics"))
            .findFirst()
            .orElseThrow();
    Map<String, Object> arguments =
        Map.of(
            "query",
            Map.of(
                "metrics", List.of("retail.total_revenue"),
                "dimensions", List.of(Map.of("id", "retail.customers.country")),
                "limit", 10));
    McpTransportContext context =
        McpTransportContext.create(
            Map.of(
                McpSemanticTools.PRINCIPAL_CONTEXT_KEY, "test-key",
                McpSemanticTools.TRACE_CONTEXT_KEY, "trace-fake-trino"));

    McpSchema.CallToolResult result =
        tool.callHandler()
            .apply(context, new McpSchema.CallToolRequest("query_metrics", arguments));

    assertThat(result.isError()).isFalse();
    assertThat(result.structuredContent())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("queryId", "fake-trino-query")
        .containsEntry("traceId", "trace-fake-trino");
    assertThat(auditEvents.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .allSatisfy(
            message ->
                assertThat(message)
                    .doesNotContain("FI")
                    .doesNotContain("1289000.20"));
    auditLogger.detachAppender(auditEvents);
  }

  private static final class AllowPolicy implements SemanticAccessPolicy {
    @Override
    public void requireAuthenticated(SemanticPrincipal principal) {
      if (principal == null || !principal.authenticated()) {
        throw new SemanticException(SemanticErrorCode.ACCESS_DENIED, "Authentication required");
      }
    }

    @Override
    public boolean canReadObject(
        SemanticPrincipal principal,
        SemanticModel model,
        SemanticModel.SemanticObject object) {
      return principal != null && principal.authenticated();
    }

    @Override
    public boolean canReadMetric(
        SemanticPrincipal principal, SemanticModel model, SemanticModel.Metric metric) {
      return principal != null && principal.authenticated();
    }

    @Override
    public boolean canViewCompiledSql(SemanticPrincipal principal) {
      return false;
    }

    @Override
    public boolean canViewPhysicalLineage(SemanticPrincipal principal) {
      return false;
    }
  }
}
