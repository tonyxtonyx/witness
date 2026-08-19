package com.acme.semantic.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.config.SemanticProperties;
import io.trino.jdbc.TrinoResultSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TrinoQueryExecutorTest {
  @Test
  void readsQueryIdFromTrinoResultSetWrapper() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    TrinoResultSet trinoResultSet = mock(TrinoResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(0);
    when(resultSet.isWrapperFor(TrinoResultSet.class)).thenReturn(true);
    when(resultSet.unwrap(TrinoResultSet.class)).thenReturn(trinoResultSet);
    when(trinoResultSet.getQueryId()).thenReturn("20260818_120000_00001_test");
    SemanticProperties.Trino trino =
        new SemanticProperties.Trino("jdbc:trino://test", "test", null, 30, 100, 1, 1000);
    TrinoQueryExecutor executor =
        new TrinoQueryExecutor(
            new SemanticProperties("semantic-model", "test", null, trino, null), dataSource);

    QueryResult result =
        executor.execute(new CompiledQuery("SELECT 1", List.of(), List.of(), "trace"), List.of());

    assertThat(result.queryId()).isEqualTo("20260818_120000_00001_test");
  }
}
