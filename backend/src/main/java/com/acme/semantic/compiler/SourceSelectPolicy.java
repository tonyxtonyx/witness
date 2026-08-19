package com.acme.semantic.compiler;

import java.util.List;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Applies parse-level restrictions to trusted SQL used as a derived object's physical source.
 *
 * <p>This policy is intentionally narrower than arbitrary Trino SQL. A model source must be one
 * read-only SELECT, and every physical table must be fully qualified. It is not a whitelist
 * renderer and does not constrain which catalogs the Trino identity can read. GitLab mode relies
 * on merge-request review for that trust decision; local mode has no review step. The validated
 * SELECT is rendered as a subquery by {@link AstSemanticSqlCompiler}.
 */
public final class SourceSelectPolicy {
  private static final int MAX_SQL_LENGTH = 64 * 1024;

  public String render(String sql) {
    return analyze(sql).rendered();
  }

  public List<String> referencedTables(String sql) {
    return analyze(sql).tables();
  }

  private Analysis analyze(String sql) {
    if (sql == null || sql.isBlank()) {
      throw invalid("Derived source SELECT is required");
    }
    if (sql.length() > MAX_SQL_LENGTH) {
      throw invalid("Derived source SELECT must be at most 64 KiB");
    }
    if (sql.contains("--") || sql.contains("/*") || sql.contains(";")) {
      throw invalid("Comments and statement separators are not allowed in a derived source");
    }
    if (sql.contains("?")) {
      throw invalid("Parameters are not allowed in a derived source");
    }

    Statement statement;
    try {
      statement = CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      throw invalid("Invalid derived source SELECT: " + e.getMessage());
    }
    if (!(statement instanceof Select select)) {
      throw invalid("Derived object source must be a read-only SELECT");
    }

    if (!(select instanceof PlainSelect plainSelect)) {
      throw invalid("Set operations are not supported in a derived source");
    }
    if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
      throw invalid("CTEs are not supported in a derived source");
    }
    if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
        || plainSelect.getIntoTempTable() != null
        || select.getForMode() != null
        || select.getForUpdateTable() != null) {
      throw invalid("Derived object source must not write or lock data");
    }

    List<String> tables = new TablesNamesFinder<Void>().getTableList(statement);
    if (tables.isEmpty()) {
      throw invalid("Derived source SELECT must read at least one physical table");
    }
    List<String> normalizedTables =
        tables.stream()
            .map(table -> table.replace("\"", "").replace("`", ""))
            .distinct()
            .sorted()
            .toList();
    for (String unquoted : normalizedTables) {
      if (unquoted.split("\\.").length != 3) {
        throw invalid(
            "Physical table must be fully qualified as catalog.schema.table: " + unquoted);
      }
    }
    return new Analysis(statement.toString(), normalizedTables);
  }

  private SqlCompilationException invalid(String message) {
    return new SqlCompilationException("0A000", message);
  }

  private record Analysis(String rendered, List<String> tables) {}
}
