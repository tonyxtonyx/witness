package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.semantic.TestModels;
import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.compiler.SemanticSqlCompiler;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.execution.QueryExecutor;
import com.acme.semantic.execution.QueryResult;
import com.acme.semantic.model.SemanticModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RestGovernanceTest {
  @Test
  void redactsCompiledSqlObjectSourcesAndRawYamlWhenPolicyIsClosed() {
    SemanticModel model = TestModels.demo();
    SemanticCatalog catalog = mock(SemanticCatalog.class);
    when(catalog.model()).thenReturn(model);
    SemanticAccessPolicy policy = mock(SemanticAccessPolicy.class);
    when(policy.canReadObject(any(), any(), any())).thenReturn(true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(ApiSecurityFilter.PRINCIPAL_ATTRIBUTE, "api-key");
    ApiController api = new ApiController(catalog, null, null, null, null, null, policy);
    SemanticModel.SemanticObject object = api.object("orders", request);
    assertThat(object.spec().source().catalog()).isNull();
    assertThat(object.spec().source().select()).isNull();
    assertThat(object.spec().dimensions()).isNotEmpty();

    SemanticSqlCompiler compiler = mock(SemanticSqlCompiler.class);
    QueryExecutor executor = mock(QueryExecutor.class);
    when(compiler.compile("SELECT status FROM retail.orders", model))
        .thenReturn(
            new CompiledQuery(
                "SELECT physical_status FROM source",
                List.of(),
                List.of(new CompiledQuery.Column("status", "varchar")),
                "trace"));
    when(executor.execute(any(), any()))
        .thenReturn(new QueryResult(List.of(), List.of(), "trino-query"));
    WorkspaceApiController workspace =
        new WorkspaceApiController(
            catalog,
            compiler,
            executor,
            null,
            new SemanticProperties("semantic-model", "test", null, null, null),
            policy);

    WorkspaceApiController.ObjectSource source = workspace.source("orders", request);
    WorkspaceApiController.QueryResponse query =
        (WorkspaceApiController.QueryResponse)
            workspace
                .query(
                    new WorkspaceApiController.QueryRequest(
                        "SELECT status FROM retail.orders", List.of()),
                    request)
                .getBody();

    assertThat(source.yaml()).isNull();
    assertThat(source.physicalSource()).isNull();
    assertThat(source.fields()).isNotEmpty();
    assertThat(query.compiledTrinoSql()).isNull();
    verify(catalog, never()).source(any());
    verify(policy, atLeastOnce()).canViewPhysicalLineage(any());
    verify(policy).canViewCompiledSql(any());
  }
}
