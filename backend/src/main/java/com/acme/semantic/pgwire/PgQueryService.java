package com.acme.semantic.pgwire;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.*;
import com.acme.semantic.execution.*;
import com.acme.semantic.model.SemanticModel;
import java.sql.Types;
import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;

@Service
public class PgQueryService {
  private final SemanticCatalog catalog;
  private final SemanticSqlCompiler compiler;
  private final QueryExecutor executor;

  public PgQueryService(
      SemanticCatalog catalog, SemanticSqlCompiler compiler, QueryExecutor executor) {
    this.catalog = catalog;
    this.compiler = compiler;
    this.executor = executor;
  }

  public Prepared prepare(String sql) {
    String normalized = sql.strip().toLowerCase(Locale.ROOT);
    if (isSystem(normalized)) return new Prepared(sql, null, metadata(normalized, List.of()));
    CompiledQuery compiled = compiler.compile(sql, catalog.model());
    return new Prepared(sql, compiled, null);
  }

  public QueryResult execute(Prepared prepared, List<Object> parameters) {
    return prepared.compiled() == null
        ? metadata(prepared.sql().strip().toLowerCase(Locale.ROOT), parameters)
        : executor.execute(prepared.compiled(), parameters);
  }

  boolean isSystem(String sql) {
    String normalized = sql.strip().toLowerCase(Locale.ROOT).replaceFirst(";\\s*$", "");
    if (normalized.startsWith("set ")
        || normalized.startsWith("show ")
        || normalized.equals("begin")
        || normalized.startsWith("begin ")
        || normalized.equals("commit")) return true;
    try {
      var statement = CCJSqlParserUtil.parse(sql);
      if (!(statement instanceof Select)) return false;
      Set<String> tables = new TablesNamesFinder<Void>().getTables(statement);
      if (tables.stream()
          .map(name -> name.toLowerCase(Locale.ROOT))
          .anyMatch(
              name ->
                  name.startsWith("pg_catalog.")
                      || name.equals("pg_catalog")
                      || name.startsWith("information_schema.")
                      || name.equals("information_schema"))) return true;
      return normalized.startsWith("select current_setting(")
          || normalized.startsWith("select version()")
          || normalized.startsWith("select current_schema")
          || normalized.startsWith("select current_database()")
          || normalized.startsWith("select session_user");
    } catch (Exception ignored) {
      return false;
    }
  }

  private QueryResult metadata(String sql, List<Object> parameters) {
    if (sql.startsWith("set ") || sql.startsWith("begin") || sql.startsWith("commit"))
      return result(List.of(), List.of());
    if (sql.startsWith("show transaction"))
      return result(
          cols(col("transaction_isolation", Types.VARCHAR, "varchar")),
          rows(row("read committed")));
    if (sql.startsWith("show search_path"))
      return result(
          cols(col("search_path", Types.VARCHAR, "varchar")),
          rows(row(String.join(",", catalog.model().domains()))));
    if (sql.contains("current_setting("))
      return result(cols(col("current_setting", Types.VARCHAR, "varchar")), rows(row("UTF8")));
    if (sql.startsWith("select version()"))
      return result(
          cols(col("version", Types.VARCHAR, "varchar")),
          rows(row("PostgreSQL 14.0 compatible Witness semantic layer")));
    if (sql.startsWith("select current_schema"))
      return result(
          cols(
              col("current_schema", Types.VARCHAR, "varchar"),
              col("session_user", Types.VARCHAR, "varchar")),
          rows(row(defaultSchema(), "semantic")));
    if (sql.contains("current_database()"))
      return result(cols(col("current_database", Types.VARCHAR, "varchar")), rows(row("semantic")));
    if (sql.contains("pg_catalog.pg_database")) return databaseInfo();
    if (sql.contains("pg_namespace") && !sql.contains("pg_class")) return schemas();
    if (sql.contains("pg_type") && !sql.contains("pg_attribute")) return postgresTypes();
    if (sql.contains("pg_constraint") && sql.contains("contype = 'p'")) return primaryKeys();
    if (sql.contains("pg_constraint") && sql.contains("contype = 'f'")) return importedKeys();
    if (sql.contains("pg_attribute"))
      return sql.contains("table_cat") ? columns() : catalogAttributes(parameters);
    if (sql.contains("pg_class"))
      return sql.contains("table_cat") ? tables() : catalogTables(parameters);
    if (sql.contains("information_schema.schemata")) return informationSchemas();
    if (sql.contains("information_schema.tables")) return tables();
    if (sql.startsWith("select") || sql.startsWith("with"))
      return result(cols(col("result", Types.VARCHAR, "varchar")), List.of());
    return result(List.of(), List.of());
  }

  private QueryResult databaseInfo() {
    return result(
        cols(
            col("oid", Types.BIGINT, "bigint"),
            col("datname", Types.VARCHAR, "varchar"),
            col("datdba", Types.BIGINT, "bigint"),
            col("encoding", Types.INTEGER, "integer"),
            col("datcollate", Types.VARCHAR, "varchar"),
            col("datctype", Types.VARCHAR, "varchar"),
            col("datistemplate", Types.BOOLEAN, "boolean"),
            col("datallowconn", Types.BOOLEAN, "boolean"),
            col("datconnlimit", Types.INTEGER, "integer"),
            col("dattablespace", Types.BIGINT, "bigint")),
        rows(row(1L, "semantic", 10L, 6, "C", "C", false, true, -1, 1663L)));
  }

  private QueryResult postgresTypes() {
    return result(
        cols(
            col("oid", Types.BIGINT, "bigint"),
            col("typname", Types.VARCHAR, "varchar"),
            col("typnamespace", Types.BIGINT, "bigint"),
            col("typlen", Types.INTEGER, "integer"),
            col("typbyval", Types.BOOLEAN, "boolean"),
            col("typtype", Types.VARCHAR, "varchar"),
            col("typcategory", Types.VARCHAR, "varchar"),
            col("typispreferred", Types.BOOLEAN, "boolean"),
            col("typdelim", Types.VARCHAR, "varchar"),
            col("typrelid", Types.BIGINT, "bigint"),
            col("typelem", Types.BIGINT, "bigint"),
            col("typarray", Types.BIGINT, "bigint"),
            col("typinput", Types.VARCHAR, "varchar"),
            col("typoutput", Types.VARCHAR, "varchar"),
            col("typreceive", Types.VARCHAR, "varchar"),
            col("typsend", Types.VARCHAR, "varchar"),
            col("typmodin", Types.VARCHAR, "varchar"),
            col("typmodout", Types.VARCHAR, "varchar"),
            col("typanalyze", Types.VARCHAR, "varchar"),
            col("typalign", Types.VARCHAR, "varchar"),
            col("typstorage", Types.VARCHAR, "varchar"),
            col("typnotnull", Types.BOOLEAN, "boolean"),
            col("typbasetype", Types.BIGINT, "bigint"),
            col("typtypmod", Types.INTEGER, "integer"),
            col("typcollation", Types.BIGINT, "bigint"),
            col("typdefault", Types.VARCHAR, "varchar"),
            col("typowner", Types.BIGINT, "bigint"),
            col("relkind", Types.VARCHAR, "varchar"),
            col("description", Types.VARCHAR, "varchar"),
            col("base_type_name", Types.VARCHAR, "varchar")),
        List.of());
  }

  private QueryResult tables() {
    List<QueryResult.Column> c =
        cols(
            col("TABLE_CAT", 12, "varchar"),
            col("TABLE_SCHEM", 12, "varchar"),
            col("TABLE_NAME", 12, "varchar"),
            col("TABLE_TYPE", 12, "varchar"),
            col("REMARKS", 12, "varchar"),
            col("TYPE_CAT", 12, "varchar"),
            col("TYPE_SCHEM", 12, "varchar"),
            col("TYPE_NAME", 12, "varchar"),
            col("SELF_REFERENCING_COL_NAME", 12, "varchar"),
            col("REF_GENERATION", 12, "varchar"));
    List<List<Object>> r = new ArrayList<>();
    for (var o : catalog.model().objects().values())
      r.add(
          row(
              "semantic",
              catalog.model().domain(o),
              o.metadata().name(),
              "TABLE",
              o.metadata().description(),
              null,
              null,
              null,
              null,
              null));
    return result(c, r);
  }

  private QueryResult schemas() {
    List<List<Object>> rows = new ArrayList<>();
    for (String schema : catalog.model().domains())
      rows.add(
          row(
              schemaOid(schema),
              schema,
              10L,
              null,
              "Semantic domain " + schema,
              schema,
              "semantic"));
    return result(
        cols(
            col("oid", Types.BIGINT, "bigint"),
            col("nspname", Types.VARCHAR, "varchar"),
            col("nspowner", Types.BIGINT, "bigint"),
            col("nspacl", Types.VARCHAR, "varchar"),
            col("description", Types.VARCHAR, "varchar"),
            col("TABLE_SCHEM", Types.VARCHAR, "varchar"),
            col("TABLE_CATALOG", Types.VARCHAR, "varchar")),
        rows);
  }

  private QueryResult informationSchemas() {
    List<List<Object>> rows = new ArrayList<>();
    for (String schema : catalog.model().domains()) rows.add(row(schema));
    return result(cols(col("schema_name", Types.VARCHAR, "varchar")), rows);
  }

  private QueryResult catalogTables(List<Object> parameters) {
    List<QueryResult.Column> c =
        cols(
            col("oid", Types.BIGINT, "bigint"),
            col("relname", Types.VARCHAR, "varchar"),
            col("relnamespace", Types.BIGINT, "bigint"),
            col("relkind", Types.VARCHAR, "varchar"),
            col("relowner", Types.BIGINT, "bigint"),
            col("relispartition", Types.BOOLEAN, "boolean"),
            col("relacl", Types.VARCHAR, "varchar"),
            col("reloptions", Types.VARCHAR, "varchar"),
            col("relpersistence", Types.VARCHAR, "varchar"),
            col("description", Types.VARCHAR, "varchar"),
            col("partition_expr", Types.VARCHAR, "varchar"),
            col("partition_key", Types.VARCHAR, "varchar"));
    Long selected = selectedSchemaOid(parameters);
    List<List<Object>> rows = new ArrayList<>();
    for (var o : catalog.model().objects().values()) {
      long schemaOid = schemaOid(catalog.model().domain(o));
      if (selected == null || selected == schemaOid)
        rows.add(
            row(
                objectOid(o),
                o.metadata().name(),
                schemaOid,
                "r",
                10L,
                false,
                null,
                null,
                "p",
                o.metadata().description(),
                null,
                null));
    }
    return result(c, rows);
  }

  private QueryResult catalogAttributes(List<Object> parameters) {
    List<QueryResult.Column> c =
        cols(
            col("relname", Types.VARCHAR, "varchar"),
            col("attrelid", Types.BIGINT, "bigint"),
            col("attname", Types.VARCHAR, "varchar"),
            col("attnum", Types.INTEGER, "integer"),
            col("attnotnull", Types.BOOLEAN, "boolean"),
            col("atttypid", Types.BIGINT, "bigint"),
            col("atttypmod", Types.INTEGER, "integer"),
            col("attndims", Types.INTEGER, "integer"),
            col("attinhcount", Types.INTEGER, "integer"),
            col("attislocal", Types.BOOLEAN, "boolean"),
            col("attidentity", Types.VARCHAR, "varchar"),
            col("attgenerated", Types.VARCHAR, "varchar"),
            col("attcollation", Types.BIGINT, "bigint"),
            col("attacl", Types.VARCHAR, "varchar"),
            col("attfdwoptions", Types.VARCHAR, "varchar"),
            col("def_value", Types.VARCHAR, "varchar"),
            col("description", Types.VARCHAR, "varchar"));
    Long selected = selectedSchemaOid(parameters);
    List<List<Object>> rows = new ArrayList<>();
    for (var o : catalog.model().objects().values()) {
      if (selected != null && selected != schemaOid(catalog.model().domain(o))) continue;
      int pos = 1;
      for (var d : o.spec().dimensions())
        rows.add(
            attributeRow(
                o, d.name(), pos++, Boolean.FALSE.equals(d.nullable()), d.type(), d.description()));
      for (var m : catalog.model().metrics().values())
        if (m.spec().baseObject().equals(o.metadata().name())
            && catalog.model().domain(m).equals(catalog.model().domain(o)))
          rows.add(
              attributeRow(
                  o,
                  m.metadata().name(),
                  pos++,
                  false,
                  m.spec().resultType(),
                  m.metadata().description()));
    }
    return result(c, rows);
  }

  private List<Object> attributeRow(
      SemanticModel.SemanticObject object,
      String name,
      int pos,
      boolean notNull,
      String type,
      String description) {
    return row(
        object.metadata().name(),
        objectOid(object),
        name,
        pos,
        notNull,
        typeOid(type),
        -1,
        0,
        0,
        true,
        "",
        "",
        0L,
        null,
        null,
        null,
        description);
  }

  private long schemaOid(String domain) {
    return 20000L + Math.floorMod(domain.hashCode(), 1_000_000);
  }

  private long objectOid(SemanticModel.SemanticObject object) {
    return 1_100_000L
        + Math.floorMod(
            (catalog.model().domain(object) + "." + object.metadata().name()).hashCode(),
            1_000_000);
  }

  private Long selectedSchemaOid(List<Object> parameters) {
    if (parameters == null || parameters.isEmpty() || parameters.getFirst() == null) return null;
    Object value = parameters.getFirst();
    try {
      return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String defaultSchema() {
    return catalog.model().domains().iterator().next();
  }

  private long typeOid(String type) {
    String t = type.toLowerCase(Locale.ROOT);
    if (t.startsWith("bigint")) return 20;
    if (t.startsWith("integer") || t.startsWith("int")) return 23;
    if (t.startsWith("decimal") || t.startsWith("numeric")) return 1700;
    if (t.startsWith("timestamp")) return 1114;
    if (t.startsWith("boolean")) return 16;
    return 1043;
  }

  private QueryResult columns() {
    List<QueryResult.Column> c =
        cols(
            "TABLE_CAT",
            "TABLE_SCHEM",
            "TABLE_NAME",
            "COLUMN_NAME",
            "DATA_TYPE",
            "TYPE_NAME",
            "COLUMN_SIZE",
            "BUFFER_LENGTH",
            "DECIMAL_DIGITS",
            "NUM_PREC_RADIX",
            "NULLABLE",
            "REMARKS",
            "COLUMN_DEF",
            "SQL_DATA_TYPE",
            "SQL_DATETIME_SUB",
            "CHAR_OCTET_LENGTH",
            "ORDINAL_POSITION",
            "IS_NULLABLE",
            "SCOPE_CATALOG",
            "SCOPE_SCHEMA",
            "SCOPE_TABLE",
            "SOURCE_DATA_TYPE",
            "IS_AUTOINCREMENT",
            "IS_GENERATEDCOLUMN");
    List<List<Object>> rows = new ArrayList<>();
    for (var o : catalog.model().objects().values()) {
      String domain = catalog.model().domain(o);
      int pos = 1;
      for (var d : o.spec().dimensions())
        rows.add(
            row(
                "semantic",
                domain,
                o.metadata().name(),
                d.name(),
                PgTypeMapper.jdbcType(d.type()),
                d.type(),
                255,
                null,
                0,
                10,
                Boolean.FALSE.equals(d.nullable()) ? 0 : 1,
                d.description(),
                null,
                null,
                null,
                255,
                pos++,
                Boolean.FALSE.equals(d.nullable()) ? "NO" : "YES",
                null,
                null,
                null,
                null,
                "NO",
                "NO"));
      for (var m : catalog.model().metrics().values())
        if (m.spec().baseObject().equals(o.metadata().name())
            && catalog.model().domain(m).equals(domain))
          rows.add(
              row(
                  "semantic",
                  domain,
                  o.metadata().name(),
                  m.metadata().name(),
                  PgTypeMapper.jdbcType(m.spec().resultType()),
                  m.spec().resultType(),
                  255,
                  null,
                  0,
                  10,
                  1,
                  m.metadata().description(),
                  null,
                  null,
                  null,
                  255,
                  pos++,
                  "YES",
                  null,
                  null,
                  null,
                  null,
                  "NO",
                  "YES"));
    }
    return result(c, rows);
  }

  private QueryResult primaryKeys() {
    List<QueryResult.Column> c =
        cols("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
    List<List<Object>> rows = new ArrayList<>();
    for (var o : catalog.model().objects().values()) {
      short i = 1;
      for (String k : o.spec().primaryKey())
        rows.add(
            row(
                "semantic",
                catalog.model().domain(o),
                o.metadata().name(),
                k,
                i++,
                o.metadata().name() + "_pkey"));
    }
    return result(c, rows);
  }

  private QueryResult importedKeys() {
    List<QueryResult.Column> c =
        cols(
            "PKTABLE_CAT",
            "PKTABLE_SCHEM",
            "PKTABLE_NAME",
            "PKCOLUMN_NAME",
            "FKTABLE_CAT",
            "FKTABLE_SCHEM",
            "FKTABLE_NAME",
            "FKCOLUMN_NAME",
            "KEY_SEQ",
            "UPDATE_RULE",
            "DELETE_RULE",
            "FK_NAME",
            "PK_NAME",
            "DEFERRABILITY");
    List<List<Object>> rows = new ArrayList<>();
    for (var o : catalog.model().objects().values())
      for (var rel : o.spec().relationships()) {
        var target = catalog.model().objects().get(rel.targetObject());
        if (target == null) continue;
        for (int i = 0; i < rel.sourceFields().size(); i++)
          rows.add(
              row(
                  "semantic",
                  catalog.model().domain(target),
                  rel.targetObject(),
                  rel.targetFields().get(i),
                  "semantic",
                  catalog.model().domain(o),
                  o.metadata().name(),
                  rel.sourceFields().get(i),
                  (short) (i + 1),
                  (short) 3,
                  (short) 3,
                  rel.name(),
                  rel.targetObject() + "_pkey",
                  (short) 7));
      }
    return result(c, rows);
  }

  private QueryResult.Column col(String n, int t, String tn) {
    return new QueryResult.Column(n, t, tn, true);
  }

  private List<QueryResult.Column> cols(QueryResult.Column... c) {
    return List.of(c);
  }

  private List<QueryResult.Column> cols(String... names) {
    return Arrays.stream(names).map(n -> col(n, Types.VARCHAR, "varchar")).toList();
  }

  private List<List<Object>> rows(List<Object>... r) {
    return List.of(r);
  }

  private List<Object> row(Object... v) {
    return Arrays.asList(v);
  }

  private QueryResult result(List<QueryResult.Column> c, List<List<Object>> r) {
    return new QueryResult(c, r, null);
  }

  public record Prepared(String sql, CompiledQuery compiled, QueryResult staticResult) {
    public List<CompiledQuery.Column> columns() {
      return compiled == null
          ? staticResult.columns().stream()
              .map(c -> new CompiledQuery.Column(c.name(), c.typeName()))
              .toList()
          : compiled.columns();
    }
  }
}
