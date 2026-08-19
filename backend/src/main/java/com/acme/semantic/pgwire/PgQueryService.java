package com.acme.semantic.pgwire;

import com.acme.semantic.catalog.SemanticCatalog;
import com.acme.semantic.compiler.*;
import com.acme.semantic.execution.*;
import com.acme.semantic.model.SemanticModel;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.*;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;

@Service
public class PgQueryService {
  private final SemanticCatalog catalog;
  private final SemanticSqlCompiler compiler;
  private final QueryExecutor executor;
  private final SemanticForeignKeyProjector foreignKeyProjector =
      new SemanticForeignKeyProjector();

  public PgQueryService(
      SemanticCatalog catalog, SemanticSqlCompiler compiler, QueryExecutor executor) {
    this.catalog = catalog;
    this.compiler = compiler;
    this.executor = executor;
  }

  public Prepared prepare(String sql) {
    if (commentFree(sql).isBlank())
      return new Prepared(sql, null, result(List.of(), List.of()));
    String normalized = sql.strip().toLowerCase(Locale.ROOT);
    if (isSystem(normalized)) return new Prepared(sql, null, metadata(sql, List.of()));
    CompiledQuery compiled = compiler.compile(sql, catalog.model());
    return new Prepared(sql, compiled, null);
  }

  public QueryResult execute(Prepared prepared, List<Object> parameters) {
    return execute(prepared, parameters, null);
  }

  public QueryResult execute(
      Prepared prepared, List<Object> parameters, SessionSettings session) {
    return prepared.compiled() == null
        ? metadata(prepared.sql(), parameters, session)
        : executor.execute(prepared.compiled(), parameters);
  }

  public String defaultSchemaName() {
    return defaultSchema();
  }

  public boolean hasSchema(String schema) {
    return catalog.model().domains().contains(schema);
  }

  boolean isSystem(String sql) {
    String normalized = sql.strip().toLowerCase(Locale.ROOT).replaceFirst(";\\s*$", "");
    if (normalized.isEmpty()
        || normalized.startsWith("set ")
        || normalized.startsWith("show ")
        || normalized.equals("begin")
        || normalized.startsWith("begin ")
        || normalized.startsWith("start transaction")
        || normalized.equals("commit")
        || normalized.equals("end")
        || normalized.equals("rollback")
        || normalized.equals("abort")
        || constantSelect(cleanConstantSql(normalized)) != null) return true;
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
    return metadata(sql, parameters, null);
  }

  private QueryResult metadata(
      String sql, List<Object> parameters, SessionSettings session) {
    String normalized = sql.strip().toLowerCase(Locale.ROOT).replaceFirst(";\\s*$", "");
    if (normalized.isEmpty()
        || normalized.startsWith("set ")
        || normalized.startsWith("begin")
        || normalized.startsWith("start transaction")
        || normalized.startsWith("commit")
        || normalized.equals("end")
        || normalized.startsWith("rollback")
        || normalized.equals("abort"))
      return result(List.of(), List.of());
    QueryResult constant = constantSelect(commentFree(sql).strip().replaceFirst(";\\s*$", ""));
    if (constant != null) return constant;
    if (normalized.startsWith("show transaction"))
      return result(
          cols(col("transaction_isolation", Types.VARCHAR, "varchar")),
          rows(row("read committed")));
    if (normalized.startsWith("show search_path"))
      return result(
          cols(col("search_path", Types.VARCHAR, "varchar")),
          rows(
              row(
                  session == null
                      ? String.join(",", catalog.model().domains())
                      : session.searchPath())));
    if (normalized.contains("current_setting("))
      return result(
          cols(col("current_setting", Types.VARCHAR, "varchar")),
          rows(row(currentSetting(normalized, session))));
    if (normalized.matches("select\\s+version\\(\\)"))
      return result(
          cols(col("version", Types.VARCHAR, "varchar")),
          rows(row("PostgreSQL 14.0 compatible Witness semantic layer")));
    if (normalized.matches("select\\s+current_schema\\s*\\(\\s*\\)"))
      return result(
          cols(col("current_schema", Types.VARCHAR, "varchar")),
          rows(row(session == null ? defaultSchema() : session.currentSchema())));
    if (normalized.startsWith("select current_schema"))
      return result(
          cols(
              col("current_schema", Types.VARCHAR, "varchar"),
              col("session_user", Types.VARCHAR, "varchar")),
          rows(row(session == null ? defaultSchema() : session.currentSchema(), "semantic")));
    if (normalized.contains("current_database()"))
      return result(cols(col("current_database", Types.VARCHAR, "varchar")), rows(row("semantic")));
    if (normalized.matches("select\\s+(?:session_user|current_user)"))
      return result(cols(col("session_user", Types.VARCHAR, "varchar")), rows(row("semantic")));
    if (normalized.matches("select\\s+pg_backend_pid\\s*\\(\\s*\\)"))
      return result(cols(col("pg_backend_pid", Types.INTEGER, "integer")), rows(row(1)));
    if (normalized.contains("pg_catalog.pg_settings")
        && normalized.contains("max_index_keys")) return maxIndexKeys();
    if (normalized.startsWith("select a.attname, a.atttypid"))
      return bestRowIdentifier(sql, parameters);
    if (isPrimaryKeyMetadataQuery(normalized)) return primaryKeys(sql);
    if (normalized.contains("pg_constraint") && normalized.contains("contype = 'f'"))
      return foreignKeys(sql);
    if (normalized.contains("information_schema.referential_constraints"))
      return referentialConstraints();
    if (normalized.contains("information_schema.key_column_usage")) return keyColumnUsage();
    if (normalized.contains("information_schema.table_constraints")) return tableConstraints();
    if (normalized.contains("pg_catalog.pg_database")) return databaseInfo();
    if (normalized.contains("pg_type") && normalized.contains("as is_array"))
      return pgJdbcTypeCache();
    if (normalized.startsWith("select t.typname,t.oid from pg_catalog.pg_type"))
      return pgJdbcTypeNames();
    if (normalized.contains("pg_type") && !normalized.contains("pg_attribute"))
      return postgresTypes();
    if (normalized.contains("from pg_catalog.pg_class")
        && normalized.contains("where 1<>1")) return pgClassShapeProbe();
    QueryResult projectedCatalog = simpleCatalogProjection(sql, parameters);
    if (projectedCatalog != null) return projectedCatalog;
    if (normalized.contains("pg_namespace") && !normalized.contains("pg_class")) return schemas();
    if (isPgJdbcColumnsQuery(normalized)) return pgJdbcColumns(sql, parameters);
    if (normalized.contains("pg_attribute"))
      return normalized.contains("table_cat")
          ? columns(sql, parameters)
          : catalogAttributes(sql, parameters);
    if (normalized.contains("pg_class"))
      return normalized.contains("table_cat")
          ? tables(sql, parameters)
          : catalogTables(sql, parameters);
    if (normalized.contains("information_schema.schemata")) return informationSchemas();
    if (normalized.contains("information_schema.tables")) return tables();
    if (normalized.startsWith("select") || normalized.startsWith("with"))
      return result(cols(col("result", Types.VARCHAR, "varchar")), List.of());
    return result(List.of(), List.of());
  }

  private boolean isPrimaryKeyMetadataQuery(String sql) {
    return (sql.contains("pg_constraint") && sql.contains("contype = 'p'"))
        || (sql.contains("pg_index") && sql.contains("indisprimary"));
  }

  private QueryResult maxIndexKeys() {
    return result(cols(col("setting", Types.INTEGER, "integer")), rows(row(32)));
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
    List<QueryResult.Column> columns =
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
            col("base_type_name", Types.VARCHAR, "varchar"));
    return result(
        columns,
        rows(
            typeRow(16, "bool", 1, true, "B", 1000),
            typeRow(17, "bytea", -1, false, "U", 1001),
            typeRow(20, "int8", 8, true, "N", 1016),
            typeRow(21, "int2", 2, true, "N", 1005),
            typeRow(23, "int4", 4, true, "N", 1007),
            typeRow(25, "text", -1, false, "S", 1009),
            typeRow(114, "json", -1, false, "U", 199),
            typeRow(700, "float4", 4, true, "N", 1021),
            typeRow(701, "float8", 8, true, "N", 1022),
            typeRow(1043, "varchar", -1, false, "S", 1015),
            typeRow(1082, "date", 4, true, "D", 1182),
            typeRow(1083, "time", 8, true, "D", 1183),
            typeRow(1114, "timestamp", 8, true, "D", 1115),
            typeRow(1184, "timestamptz", 8, true, "D", 1185),
            typeRow(1700, "numeric", -1, false, "N", 1231),
            typeRow(2950, "uuid", 16, false, "U", 2951),
            typeRow(3802, "jsonb", -1, false, "U", 3807)));
  }

  private QueryResult pgJdbcTypeCache() {
    List<QueryResult.Column> columns =
        cols(
            col("is_array", Types.BOOLEAN, "boolean"),
            col("typtype", Types.VARCHAR, "varchar"),
            col("typname", Types.VARCHAR, "varchar"),
            col("oid", Types.BIGINT, "bigint"));
    return result(
        columns,
        rows(
            row(false, "b", "bool", 16L),
            row(false, "b", "bytea", 17L),
            row(false, "b", "int8", 20L),
            row(false, "b", "int2", 21L),
            row(false, "b", "int4", 23L),
            row(false, "b", "text", 25L),
            row(false, "b", "json", 114L),
            row(false, "b", "float4", 700L),
            row(false, "b", "float8", 701L),
            row(false, "b", "varchar", 1043L),
            row(false, "b", "date", 1082L),
            row(false, "b", "time", 1083L),
            row(false, "b", "timestamp", 1114L),
            row(false, "b", "timestamptz", 1184L),
            row(false, "b", "numeric", 1700L),
            row(false, "b", "uuid", 2950L),
            row(false, "b", "jsonb", 3802L)));
  }

  private QueryResult pgJdbcTypeNames() {
    List<QueryResult.Column> columns =
        cols(
            col("typname", Types.VARCHAR, "varchar"),
            col("oid", Types.BIGINT, "bigint"));
    return result(
        columns,
        rows(
            row("bool", 16L),
            row("bytea", 17L),
            row("int8", 20L),
            row("int2", 21L),
            row("int4", 23L),
            row("text", 25L),
            row("json", 114L),
            row("float4", 700L),
            row("float8", 701L),
            row("varchar", 1043L),
            row("date", 1082L),
            row("time", 1083L),
            row("timestamp", 1114L),
            row("timestamptz", 1184L),
            row("numeric", 1700L),
            row("uuid", 2950L),
            row("jsonb", 3802L)));
  }

  private List<Object> typeRow(
      long oid, String name, int length, boolean byValue, String category, long arrayOid) {
    return row(
        oid,
        name,
        11L,
        length,
        byValue,
        "b",
        category,
        false,
        ",",
        0L,
        0L,
        arrayOid,
        name + "in",
        name + "out",
        name + "recv",
        name + "send",
        "-",
        "-",
        "-",
        length == 8 ? "d" : "i",
        length < 0 ? "x" : "p",
        false,
        0L,
        -1,
        0L,
        null,
        10L,
        null,
        "Witness PostgreSQL-compatible type " + name,
        null);
  }

  private QueryResult tables() {
    return tables("", List.of());
  }

  private QueryResult tables(String sql, List<Object> parameters) {
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
    String schemaPattern = predicatePattern(sql, "n.nspname", parameters);
    String tablePattern = predicatePattern(sql, "c.relname", parameters);
    for (var o : catalog.model().objects().values()) {
      String domain = catalog.model().domain(o);
      if (!matchesPattern(domain, schemaPattern)
          || !matchesPattern(o.metadata().name(), tablePattern)) continue;
      r.add(
          row(
              "semantic",
              domain,
              o.metadata().name(),
              "TABLE",
              o.metadata().description(),
              null,
              null,
              null,
              null,
              null));
    }
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

  private QueryResult pgClassShapeProbe() {
    return result(cols(col("reltype", Types.BIGINT, "oid")), List.of());
  }

  private QueryResult simpleCatalogProjection(String sql, List<Object> parameters) {
    PlainSelect select;
    try {
      var statement = CCJSqlParserUtil.parse(sql);
      if (!(statement instanceof Select parsed)) return null;
      select = parsed.getPlainSelect();
    } catch (Exception ignored) {
      return null;
    }
    if (select == null || select.getSelectItems().isEmpty()) return null;
    List<Column> projection = new ArrayList<>();
    List<String> outputNames = new ArrayList<>();
    for (SelectItem<?> item : select.getSelectItems()) {
      if (!(item.getExpression() instanceof Column column)) return null;
      projection.add(column);
      outputNames.add(
          item.getAlias() == null ? column.getColumnName() : item.getAlias().getName());
    }

    Set<String> tables =
        new TablesNamesFinder<Void>()
            .getTables((net.sf.jsqlparser.statement.Statement) select)
            .stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    boolean attributes = tables.stream().anyMatch(name -> name.endsWith("pg_attribute"));
    boolean relations = tables.stream().anyMatch(name -> name.endsWith("pg_class"));
    boolean namespaces = tables.stream().anyMatch(name -> name.endsWith("pg_namespace"));
    if (!attributes && !relations && !namespaces) return null;

    String schemaPattern =
        firstNonNull(
            predicatePattern(sql, "n.nspname", parameters),
            predicatePattern(sql, "nspname", parameters));
    String relationPattern =
        firstNonNull(
            predicatePattern(sql, "c.relname", parameters),
            predicatePattern(sql, "relname", parameters));
    String attributePattern =
        firstNonNull(
            predicatePattern(sql, "a.attname", parameters),
            predicatePattern(sql, "attname", parameters));
    Long schemaOid = equalityLong(sql, "c.relnamespace", parameters);
    Long relationOid = equalityLong(sql, "c.oid", parameters);

    List<Map<String, Object>> source = new ArrayList<>();
    if (relations || attributes) {
      for (var object : catalog.model().objects().values()) {
        String domain = catalog.model().domain(object);
        long objectSchemaOid = schemaOid(domain);
        long objectRelationOid = objectOid(object);
        if (!matchesPattern(domain, schemaPattern)
            || !matchesPattern(object.metadata().name(), relationPattern)
            || (schemaOid != null && schemaOid != objectSchemaOid)
            || (relationOid != null && relationOid != objectRelationOid)) continue;
        if (attributes) {
          int position = 1;
          for (var dimension : object.spec().dimensions()) {
            if (matchesPattern(dimension.name(), attributePattern))
              source.add(
                  catalogProjectionRow(
                      domain,
                      objectSchemaOid,
                      object,
                      objectRelationOid,
                      dimension.name(),
                      position,
                      dimension.type(),
                      Boolean.FALSE.equals(dimension.nullable())));
            position++;
          }
          for (var metric : catalog.model().metrics().values()) {
            if (!belongsTo(metric, object)) continue;
            if (matchesPattern(metric.metadata().name(), attributePattern))
              source.add(
                  catalogProjectionRow(
                      domain,
                      objectSchemaOid,
                      object,
                      objectRelationOid,
                      metric.metadata().name(),
                      position,
                      metric.spec().resultType(),
                      false));
            position++;
          }
        } else {
          source.add(
              catalogProjectionRow(
                  domain,
                  objectSchemaOid,
                  object,
                  objectRelationOid,
                  null,
                  0,
                  null,
                  false));
        }
      }
    } else {
      for (String domain : catalog.model().domains()) {
        if (!matchesPattern(domain, schemaPattern)) continue;
        Map<String, Object> row = new HashMap<>();
        putCatalogValue(row, "n", "oid", schemaOid(domain));
        putCatalogValue(row, "n", "nspname", domain);
        putCatalogValue(row, "n", "nspowner", 10L);
        source.add(row);
      }
    }

    List<QueryResult.Column> columns = new ArrayList<>();
    for (int i = 0; i < projection.size(); i++) {
      String type = catalogColumnType(projection.get(i).getColumnName());
      columns.add(col(outputNames.get(i), PgTypeMapper.jdbcType(type), type));
    }
    List<List<Object>> rows = new ArrayList<>();
    for (Map<String, Object> sourceRow : source) {
      List<Object> values = new ArrayList<>();
      for (Column column : projection) {
        String table = column.getTable() == null ? "" : column.getTable().getName();
        String qualified =
            table == null || table.isBlank()
                ? column.getColumnName().toLowerCase(Locale.ROOT)
                : (table + "." + column.getColumnName()).toLowerCase(Locale.ROOT);
        values.add(sourceRow.get(qualified));
      }
      rows.add(values);
    }
    return result(columns, rows);
  }

  private Map<String, Object> catalogProjectionRow(
      String domain,
      long schemaOid,
      SemanticModel.SemanticObject object,
      long relationOid,
      String attribute,
      int position,
      String type,
      boolean notNull) {
    Map<String, Object> row = new HashMap<>();
    putCatalogValue(row, "n", "oid", schemaOid);
    putCatalogValue(row, "n", "nspname", domain);
    putCatalogValue(row, "c", "oid", relationOid);
    putCatalogValue(row, "c", "relname", object.metadata().name());
    putCatalogValue(row, "c", "relnamespace", schemaOid);
    putCatalogValue(row, "c", "relkind", "r");
    putCatalogValue(row, "c", "relowner", 10L);
    putCatalogValue(row, "c", "relispartition", false);
    if (attribute != null) {
      putCatalogValue(row, "a", "attrelid", relationOid);
      putCatalogValue(row, "a", "attname", attribute);
      putCatalogValue(row, "a", "attnum", position);
      putCatalogValue(row, "a", "attnotnull", notNull);
      putCatalogValue(row, "a", "atttypid", (long) PgTypeMapper.oid(type));
      putCatalogValue(row, "a", "atttypmod", -1);
    }
    return row;
  }

  private void putCatalogValue(
      Map<String, Object> row, String table, String column, Object value) {
    row.put(table + "." + column, value);
    row.putIfAbsent(column, value);
  }

  private String catalogColumnType(String column) {
    String name = column.toLowerCase(Locale.ROOT);
    if (name.equals("relispartition") || name.startsWith("attnot")) return "boolean";
    if (name.equals("attnum") || name.equals("atttypmod")) return "integer";
    if (name.equals("oid")
        || name.endsWith("oid")
        || name.equals("relnamespace")
        || name.equals("attrelid")
        || name.equals("atttypid")
        || name.equals("relowner")
        || name.equals("nspowner")) return "bigint";
    return "varchar";
  }

  private QueryResult catalogTables(String sql, List<Object> parameters) {
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
    Long selectedSchema = equalityLong(sql, "c.relnamespace", parameters);
    String selectedObject =
        firstNonNull(
            equalityValue(sql, "c.relname", parameters),
            equalityValue(sql, "relname", parameters));
    boolean requiresSchema = hasEqualityPredicate(sql, "c.relnamespace");
    boolean requiresObject =
        hasEqualityPredicate(sql, "c.relname") || hasEqualityPredicate(sql, "relname");
    if ((requiresSchema && selectedSchema == null) || (requiresObject && selectedObject == null)) {
      return result(c, List.of());
    }
    List<List<Object>> rows = new ArrayList<>();
    for (var o : catalog.model().objects().values()) {
      long schemaOid = schemaOid(catalog.model().domain(o));
      if ((selectedSchema == null || selectedSchema == schemaOid)
          && (selectedObject == null || selectedObject.equals(o.metadata().name()))) {
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
    }
    return result(c, rows);
  }

  private QueryResult catalogAttributes(String sql, List<Object> parameters) {
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
    Long selectedSchema = equalityLong(sql, "c.relnamespace", parameters);
    Long selectedObject = equalityLong(sql, "c.oid", parameters);
    boolean requiresSchema = hasEqualityPredicate(sql, "c.relnamespace");
    boolean requiresObject = hasEqualityPredicate(sql, "c.oid");
    if ((requiresSchema && selectedSchema == null) || (requiresObject && selectedObject == null)) {
      return result(c, List.of());
    }
    List<List<Object>> rows = new ArrayList<>();
    for (var o : catalog.model().objects().values()) {
      if (selectedSchema != null && selectedSchema != schemaOid(catalog.model().domain(o)))
        continue;
      if (selectedObject != null && selectedObject != objectOid(o)) continue;
      int pos = 1;
      for (var d : o.spec().dimensions())
        rows.add(
            attributeRow(
                o, d.name(), pos++, Boolean.FALSE.equals(d.nullable()), d.type(), d.description()));
      for (var m : catalog.model().metrics().values())
        if (belongsTo(m, o))
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

  private Long equalityLong(String sql, String expression, List<Object> parameters) {
    String value = equalityValue(sql, expression, parameters);
    if (value == null) return null;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private boolean hasEqualityPredicate(String sql, String expression) {
    return Pattern.compile("(?i)\\b" + Pattern.quote(expression) + "\\s*=")
        .matcher(sql)
        .find();
  }

  private String equalityValue(String sql, String expression, List<Object> parameters) {
    Pattern pattern =
        Pattern.compile(
            "(?i)\\b"
                + Pattern.quote(expression)
                + "\\s*=\\s*\\(?\\s*"
                + "(?:\\$([1-9]\\d*)|(\\?)|(-?\\d+)|(?:e)?'((?:''|[^'])*)')"
                + "\\s*(?:::[a-z0-9_.\"]+)?\\s*\\)?");
    var matcher = pattern.matcher(sql);
    if (!matcher.find()) return null;
    if (matcher.group(1) != null)
      return parameter(parameters, Integer.parseInt(matcher.group(1)) - 1);
    if (matcher.group(2) != null) {
      int parameterIndex = 0;
      for (int i = 0; i < matcher.start(2); i++)
        if (sql.charAt(i) == '?') parameterIndex++;
      return parameter(parameters, parameterIndex);
    }
    if (matcher.group(3) != null) return matcher.group(3);
    return matcher.group(4).replace("''", "'");
  }

  private String parameter(List<Object> parameters, int index) {
    if (parameters == null || index < 0 || index >= parameters.size()) return null;
    Object value = parameters.get(index);
    return value == null ? null : value.toString();
  }

  private String firstNonNull(String first, String second) {
    return first != null ? first : second;
  }

  private String defaultSchema() {
    return catalog.model().domains().iterator().next();
  }

  private String currentSetting(String normalized, SessionSettings session) {
    var matcher =
        Pattern.compile("current_setting\\s*\\(\\s*'([^']+)'", Pattern.CASE_INSENSITIVE)
            .matcher(normalized);
    String key = matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    if (session != null && session.settings().containsKey(key))
      return session.settings().get(key);
    return switch (key) {
      case "client_encoding", "server_encoding" -> "UTF8";
      case "standard_conforming_strings", "integer_datetimes" -> "on";
      case "timezone" -> "UTC";
      case "datestyle" -> "ISO, MDY";
      case "transaction_isolation" -> "read committed";
      case "search_path" ->
          session == null
              ? String.join(",", catalog.model().domains())
              : session.searchPath();
      default -> "";
    };
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

  private QueryResult columns(String sql, List<Object> parameters) {
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
    String schemaPattern = predicatePattern(sql, "n.nspname", parameters);
    String tablePattern = predicatePattern(sql, "c.relname", parameters);
    String columnPattern = predicatePattern(sql, "a.attname", parameters);
    for (var o : catalog.model().objects().values()) {
      String domain = catalog.model().domain(o);
      if (!matchesPattern(domain, schemaPattern)
          || !matchesPattern(o.metadata().name(), tablePattern)) continue;
      int pos = 1;
      for (var d : o.spec().dimensions())
        if (matchesPattern(d.name(), columnPattern))
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
        if (belongsTo(m, o) && matchesPattern(m.metadata().name(), columnPattern))
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

  private boolean isPgJdbcColumnsQuery(String normalized) {
    return normalized.contains("pg_catalog.pg_attribute a")
        && normalized.contains("t.typbasetype")
        && normalized.contains("t.typtype")
        && normalized.contains("a.attlen");
  }

  private QueryResult pgJdbcColumns(String sql, List<Object> parameters) {
    List<QueryResult.Column> columns =
        cols(
            col("nspname", Types.VARCHAR, "varchar"),
            col("relname", Types.VARCHAR, "varchar"),
            col("attname", Types.VARCHAR, "varchar"),
            col("atttypid", Types.BIGINT, "bigint"),
            col("attnotnull", Types.BOOLEAN, "boolean"),
            col("atttypmod", Types.INTEGER, "integer"),
            col("attlen", Types.INTEGER, "integer"),
            col("typtypmod", Types.INTEGER, "integer"),
            col("attnum", Types.BIGINT, "bigint"),
            col("attidentity", Types.VARCHAR, "varchar"),
            col("attgenerated", Types.VARCHAR, "varchar"),
            col("adsrc", Types.VARCHAR, "varchar"),
            col("description", Types.VARCHAR, "varchar"),
            col("typbasetype", Types.BIGINT, "bigint"),
            col("typtype", Types.VARCHAR, "varchar"));
    String schemaPattern = predicatePattern(sql, "n.nspname", parameters);
    String tablePattern = predicatePattern(sql, "c.relname", parameters);
    String columnPattern = predicatePattern(sql, "attname", parameters);
    List<List<Object>> rows = new ArrayList<>();
    for (var object : catalog.model().objects().values()) {
      String domain = catalog.model().domain(object);
      if (!matchesPattern(domain, schemaPattern)
          || !matchesPattern(object.metadata().name(), tablePattern)) continue;
      long position = 1;
      for (var dimension : object.spec().dimensions()) {
        if (matchesPattern(dimension.name(), columnPattern))
          rows.add(
              pgJdbcColumnRow(
                  domain,
                  object.metadata().name(),
                  dimension.name(),
                  dimension.type(),
                  Boolean.FALSE.equals(dimension.nullable()),
                  position,
                  dimension.description(),
                  ""));
        position++;
      }
      for (var metric : catalog.model().metrics().values()) {
        if (!belongsTo(metric, object)) continue;
        if (matchesPattern(metric.metadata().name(), columnPattern))
          rows.add(
              pgJdbcColumnRow(
                  domain,
                  object.metadata().name(),
                  metric.metadata().name(),
                  metric.spec().resultType(),
                  false,
                  position,
                  metric.metadata().description(),
                  "s"));
        position++;
      }
    }
    return result(columns, rows);
  }

  private List<Object> pgJdbcColumnRow(
      String domain,
      String object,
      String field,
      String type,
      boolean notNull,
      long position,
      String description,
      String generated) {
    int oid = PgTypeMapper.oid(type);
    return row(
        domain,
        object,
        field,
        (long) oid,
        notNull,
        -1,
        PgTypeMapper.size(oid),
        -1,
        position,
        "",
        generated,
        null,
        description,
        0L,
        "b");
  }

  private String predicatePattern(
      String sql, String expression, List<Object> parameters) {
    String like = predicatePattern(sql, expression, "like", parameters);
    return like != null ? like : predicatePattern(sql, expression, "=", parameters);
  }

  private String predicatePattern(
      String sql, String expression, String operator, List<Object> parameters) {
    Pattern pattern =
        Pattern.compile(
            "(?i)\\b"
                + Pattern.quote(expression)
                + "\\s*"
                + Pattern.quote(operator)
                + "\\s*\\(?\\s*"
                + "(?:\\$([1-9]\\d*)|(\\?)|(?:e)?'((?:''|[^'])*)')");
    var matcher = pattern.matcher(sql);
    if (!matcher.find()) return null;
    if (matcher.group(1) != null)
      return parameter(parameters, Integer.parseInt(matcher.group(1)) - 1);
    if (matcher.group(2) != null) {
      int index = 0;
      for (int i = 0; i < matcher.start(2); i++) if (sql.charAt(i) == '?') index++;
      return parameter(parameters, index);
    }
    return matcher.group(3).replace("''", "'");
  }

  private boolean matchesPattern(String value, String pattern) {
    if (pattern == null) return true;
    StringBuilder regex = new StringBuilder("^");
    boolean escaped = false;
    for (char character : pattern.toCharArray()) {
      if (escaped) {
        regex.append(Pattern.quote(String.valueOf(character)));
        escaped = false;
      } else if (character == '\\') {
        escaped = true;
      } else if (character == '%') {
        regex.append(".*");
      } else if (character == '_') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(character)));
      }
    }
    if (escaped) regex.append("\\\\");
    return value.matches(regex.append('$').toString());
  }

  private QueryResult primaryKeys(String sql) {
    List<QueryResult.Column> c =
        cols("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
    List<List<Object>> rows = new ArrayList<>();
    SemanticModel model = catalog.model();
    String selectedDomain = equalityLiteral(sql, "n.nspname");
    String selectedObject = equalityLiteral(sql, "ct.relname");
    for (var o : model.objects().values()) {
      String domain = model.domain(o);
      if (selectedDomain != null && !selectedDomain.equals(domain)) continue;
      if (selectedObject != null && !selectedObject.equals(o.metadata().name())) continue;
      short i = 1;
      for (String k : o.spec().primaryKey())
        rows.add(
            row(
                "semantic",
                domain,
                o.metadata().name(),
                k,
                i++,
                o.metadata().name() + "_pkey"));
    }
    return result(c, rows);
  }

  private QueryResult bestRowIdentifier(String sql, List<Object> parameters) {
    List<QueryResult.Column> columns =
        cols(
            col("attname", Types.VARCHAR, "varchar"),
            col("atttypid", Types.BIGINT, "bigint"),
            col("atttypmod", Types.INTEGER, "integer"));
    String schemaPattern = predicatePattern(sql, "n.nspname", parameters);
    String tablePattern = predicatePattern(sql, "ct.relname", parameters);
    List<List<Object>> rows = new ArrayList<>();
    for (var object : catalog.model().objects().values()) {
      String domain = catalog.model().domain(object);
      if (!matchesPattern(domain, schemaPattern)
          || !matchesPattern(object.metadata().name(), tablePattern)) continue;
      for (String key : object.spec().primaryKey()) {
        var dimension =
            object.spec().dimensions().stream()
                .filter(candidate -> candidate.name().equals(key))
                .findFirst()
                .orElse(null);
        if (dimension != null)
          rows.add(row(key, (long) PgTypeMapper.oid(dimension.type()), -1));
      }
    }
    return result(columns, rows);
  }

  private QueryResult foreignKeys(String sql) {
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
    String primaryDomain = equalityLiteral(sql, "pkn.nspname");
    String primaryObject = equalityLiteral(sql, "pkc.relname");
    String foreignDomain = equalityLiteral(sql, "fkn.nspname");
    String foreignObject = equalityLiteral(sql, "fkc.relname");

    for (var key : foreignKeyProjector.project(catalog.model())) {
      if (primaryDomain != null && !primaryDomain.equals(key.primaryKeyDomain())) continue;
      if (primaryObject != null && !primaryObject.equals(key.primaryKeyObject())) continue;
      if (foreignDomain != null && !foreignDomain.equals(key.foreignKeyDomain())) continue;
      if (foreignObject != null && !foreignObject.equals(key.foreignKeyObject())) continue;
      for (int i = 0; i < key.foreignKeyFields().size(); i++)
        rows.add(
            row(
                "semantic",
                key.primaryKeyDomain(),
                key.primaryKeyObject(),
                key.primaryKeyFields().get(i),
                "semantic",
                key.foreignKeyDomain(),
                key.foreignKeyObject(),
                key.foreignKeyFields().get(i),
                (short) (i + 1),
                (short) DatabaseMetaData.importedKeyNoAction,
                (short) DatabaseMetaData.importedKeyNoAction,
                key.name(),
                key.primaryKeyName(),
                (short) DatabaseMetaData.importedKeyNotDeferrable));
    }
    return result(c, rows);
  }

  private QueryResult tableConstraints() {
    List<QueryResult.Column> columns =
        cols(
            "constraint_catalog",
            "constraint_schema",
            "constraint_name",
            "table_catalog",
            "table_schema",
            "table_name",
            "constraint_type",
            "is_deferrable",
            "initially_deferred",
            "enforced");
    List<List<Object>> rows = new ArrayList<>();
    SemanticModel model = catalog.model();
    for (var object : model.objects().values())
      if (!object.spec().primaryKey().isEmpty())
        rows.add(
            row(
                "semantic",
                model.domain(object),
                object.metadata().name() + "_pkey",
                "semantic",
                model.domain(object),
                object.metadata().name(),
                "PRIMARY KEY",
                "NO",
                "NO",
                "NO"));
    for (var key : foreignKeyProjector.project(model))
      rows.add(
          row(
              "semantic",
              key.foreignKeyDomain(),
              key.name(),
              "semantic",
              key.foreignKeyDomain(),
              key.foreignKeyObject(),
              "FOREIGN KEY",
              "NO",
              "NO",
              key.enforced() ? "YES" : "NO"));
    return result(columns, rows);
  }

  private QueryResult keyColumnUsage() {
    List<QueryResult.Column> columns =
        cols(
            "constraint_catalog",
            "constraint_schema",
            "constraint_name",
            "table_catalog",
            "table_schema",
            "table_name",
            "column_name",
            "ordinal_position",
            "position_in_unique_constraint");
    List<List<Object>> rows = new ArrayList<>();
    SemanticModel model = catalog.model();
    for (var object : model.objects().values())
      for (int i = 0; i < object.spec().primaryKey().size(); i++)
        rows.add(
            row(
                "semantic",
                model.domain(object),
                object.metadata().name() + "_pkey",
                "semantic",
                model.domain(object),
                object.metadata().name(),
                object.spec().primaryKey().get(i),
                i + 1,
                null));
    for (var key : foreignKeyProjector.project(model))
      for (int i = 0; i < key.foreignKeyFields().size(); i++)
        rows.add(
            row(
                "semantic",
                key.foreignKeyDomain(),
                key.name(),
                "semantic",
                key.foreignKeyDomain(),
                key.foreignKeyObject(),
                key.foreignKeyFields().get(i),
                i + 1,
                i + 1));
    return result(columns, rows);
  }

  private QueryResult referentialConstraints() {
    List<QueryResult.Column> columns =
        cols(
            "constraint_catalog",
            "constraint_schema",
            "constraint_name",
            "unique_constraint_catalog",
            "unique_constraint_schema",
            "unique_constraint_name",
            "match_option",
            "update_rule",
            "delete_rule");
    List<List<Object>> rows = new ArrayList<>();
    for (var key : foreignKeyProjector.project(catalog.model()))
      rows.add(
          row(
              "semantic",
              key.foreignKeyDomain(),
              key.name(),
              "semantic",
              key.primaryKeyDomain(),
              key.primaryKeyName(),
              "NONE",
              "NO ACTION",
              "NO ACTION"));
    return result(columns, rows);
  }

  private String equalityLiteral(String sql, String expression) {
    Pattern pattern =
        Pattern.compile(
            "(?i)\\b"
                + Pattern.quote(expression)
                + "\\s*=\\s*(?:e)?'((?:''|[^'])*)'");
    var matcher = pattern.matcher(sql);
    return matcher.find() ? matcher.group(1).replace("''", "'") : null;
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

  private QueryResult constantSelect(String normalized) {
    var select = Pattern.compile("(?is)^select\\s+(.+)$").matcher(normalized);
    if (!select.matches()) return null;
    List<String> items = splitConstantItems(select.group(1));
    List<QueryResult.Column> columns = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (String item : items) {
      var aliased =
          Pattern.compile(
                  "(?is)^(.+?)(?:\\s+as\\s+|\\s+)(\\\"?[a-z_][a-z0-9_$]*\\\"?)$")
              .matcher(item.strip());
      String expression = item.strip();
      String alias = "?column?";
      if (aliased.matches()) {
        expression = aliased.group(1).strip();
        alias = aliased.group(2).replace("\"", "");
      }
      if (expression.matches("[+-]?\\d+")) {
        long value = Long.parseLong(expression);
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
          columns.add(col(alias, Types.INTEGER, "integer"));
          values.add((int) value);
        } else {
          columns.add(col(alias, Types.BIGINT, "bigint"));
          values.add(value);
        }
      } else if (expression.matches("(?is)'(?:''|[^'])*'")) {
        columns.add(col(alias, Types.VARCHAR, "varchar"));
        values.add(expression.substring(1, expression.length() - 1).replace("''", "'"));
      } else if (expression.equalsIgnoreCase("null")) {
        columns.add(col(alias, Types.VARCHAR, "varchar"));
        values.add(null);
      } else if (expression.equalsIgnoreCase("true") || expression.equalsIgnoreCase("false")) {
        columns.add(col(alias, Types.BOOLEAN, "boolean"));
        values.add(Boolean.parseBoolean(expression));
      } else {
        return null;
      }
    }
    return result(columns, rows(values));
  }

  private String cleanConstantSql(String sql) {
    return commentFree(sql).strip();
  }

  private boolean belongsTo(
      SemanticModel.Metric metric, SemanticModel.SemanticObject object) {
    return catalog
            .model()
            .resolveObject(metric.spec().baseObject(), catalog.model().domain(metric))
            .value()
        == object;
  }

  private String commentFree(String sql) {
    return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
  }

  private List<String> splitConstantItems(String source) {
    List<String> items = new ArrayList<>();
    int start = 0;
    boolean quoted = false;
    for (int i = 0; i < source.length(); i++) {
      char character = source.charAt(i);
      if (character == '\'') {
        if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '\'') i++;
        else quoted = !quoted;
      } else if (character == ',' && !quoted) {
        items.add(source.substring(start, i));
        start = i + 1;
      }
    }
    items.add(source.substring(start));
    return items;
  }

  public record Prepared(
      String sql, CompiledQuery compiled, QueryResult staticResult, int[] parameterOids) {
    public Prepared(String sql, CompiledQuery compiled, QueryResult staticResult) {
      this(
          sql,
          compiled,
          staticResult,
          new int[compiled == null ? 0 : compiled.parameters().size()]);
    }

    public Prepared {
      parameterOids = parameterOids == null ? new int[0] : parameterOids.clone();
    }

    public Prepared withParameterOids(int[] oids) {
      return new Prepared(sql, compiled, staticResult, oids);
    }

    public int parameterCount() {
      return Math.max(parameterOids.length, compiled == null ? 0 : compiled.parameters().size());
    }

    public int parameterOid(int index) {
      return index >= 0 && index < parameterOids.length ? parameterOids[index] : 0;
    }

    public boolean empty() {
      return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
          .replaceAll("(?m)--.*$", " ")
          .strip()
          .replaceFirst(";\\s*$", "")
          .isEmpty();
    }

    public String commandTag() {
      String normalized = sql.strip().toLowerCase(Locale.ROOT).replaceFirst(";\\s*$", "");
      if (normalized.equals("begin")
          || normalized.startsWith("begin ")
          || normalized.startsWith("start transaction")) return "BEGIN";
      if (normalized.equals("commit") || normalized.equals("end")) return "COMMIT";
      if (normalized.equals("rollback") || normalized.equals("abort")) return "ROLLBACK";
      if (normalized.startsWith("set ")) return "SET";
      if (normalized.startsWith("show ")) return "SHOW";
      return null;
    }

    public List<CompiledQuery.Column> columns() {
      return compiled == null
          ? staticResult.columns().stream()
              .map(c -> new CompiledQuery.Column(c.name(), c.typeName()))
              .toList()
          : compiled.columns();
    }
  }

  public record SessionSettings(
      String currentSchema, String searchPath, Map<String, String> settings) {
    public SessionSettings {
      settings = Map.copyOf(settings);
    }
  }
}
