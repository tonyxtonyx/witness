package com.acme.semantic.execution;

import com.acme.semantic.compiler.CompiledQuery;
import com.acme.semantic.config.SemanticProperties;
import io.trino.jdbc.TrinoResultSet;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class TrinoQueryExecutor implements QueryExecutor {
  private static final Logger log = LoggerFactory.getLogger(TrinoQueryExecutor.class);
  private final SemanticProperties.Trino config;
  private final DataSource dataSource;

  public TrinoQueryExecutor(
      SemanticProperties properties, @Qualifier("trinoDataSource") DataSource dataSource) {
    this.config = properties.trino();
    this.dataSource = dataSource;
  }

  @Override
  public QueryResult execute(CompiledQuery query, List<Object> parameters) {
    long started = System.nanoTime();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(query.trinoSql())) {
      statement.setQueryTimeout(config.timeoutSeconds());
      statement.setMaxRows(config.maxRows());
      for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
      try (ResultSet rs = statement.executeQuery()) {
        ResultSetMetaData md = rs.getMetaData();
        List<QueryResult.Column> columns = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
          String name =
              i <= query.columns().size()
                  ? query.columns().get(i - 1).name()
                  : md.getColumnLabel(i);
          columns.add(
              new QueryResult.Column(
                  name,
                  md.getColumnType(i),
                  md.getColumnTypeName(i),
                  md.isNullable(i) != ResultSetMetaData.columnNoNulls));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < config.maxRows()) {
          List<Object> row = new ArrayList<>();
          for (int i = 1; i <= md.getColumnCount(); i++) row.add(rs.getObject(i));
          rows.add(row);
        }
        String queryId = queryId(rs);
        log.info(
            "semantic query executed correlationId={} elapsedMs={} rows={}",
            query.correlationId(),
            (System.nanoTime() - started) / 1_000_000,
            rows.size());
        return new QueryResult(columns, rows, queryId);
      }
    } catch (SQLException e) {
      throw new QueryExecutionException(
          e.getSQLState() == null ? "XX000" : e.getSQLState(),
          "Trino execution failed: " + e.getMessage(),
          e);
    }
  }

  private String queryId(ResultSet resultSet) {
    try {
      if (resultSet instanceof TrinoResultSet trino) return trino.getQueryId();
      if (resultSet.isWrapperFor(TrinoResultSet.class))
        return resultSet.unwrap(TrinoResultSet.class).getQueryId();
    } catch (Exception | LinkageError ignored) {
      // Query IDs are optional for non-Trino drivers and test doubles.
    }
    return null;
  }
}
