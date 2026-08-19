package com.acme.semantic.api;

import com.acme.semantic.compiler.SqlCompilationException;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.model.SemanticModel;
import java.util.ArrayList;
import java.util.List;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Authorizes SQL table references before compilation can reveal fields or physical mappings. */
@Component
public class SemanticSqlReferenceAuthorizer {
  private final SemanticResourceAccess access;

  public SemanticSqlReferenceAuthorizer(SemanticResourceAccess access) {
    this.access = access;
  }

  public void requireReadableReferences(
      SemanticPrincipal principal, String sql, SemanticModel model) {
    for (Table table : tables(sql)) {
      try {
        String schema = unquote(table.getSchemaName());
        if (schema != null
            && schema.equalsIgnoreCase(model.project().spec().semanticSchema()))
          access.readableObject(principal, unquote(table.getName()));
        else access.readableObject(principal, unquote(table.getName()), schema);
      } catch (ResponseStatusException exception) {
        if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value())
          throw unknownObject();
        throw new SqlCompilationException("42702", "Ambiguous semantic object");
      }
    }
  }

  private List<Table> tables(String sql) {
    try {
      if (!(CCJSqlParserUtil.parse(sql) instanceof Select select)) return List.of();
      PlainSelect plain = select.getPlainSelect();
      List<Table> tables = new ArrayList<>();
      if (plain.getFromItem() instanceof Table table) tables.add(table);
      if (plain.getJoins() != null)
        for (Join join : plain.getJoins())
          if (join.getRightItem() instanceof Table table) tables.add(table);
      return List.copyOf(tables);
    } catch (Exception exception) {
      return List.of();
    }
  }

  private String unquote(String value) {
    return value == null ? null : value.replaceAll("^\"|\"$", "");
  }

  private SqlCompilationException unknownObject() {
    return new SqlCompilationException("42P01", "Unknown semantic object");
  }
}
