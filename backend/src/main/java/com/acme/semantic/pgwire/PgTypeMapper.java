package com.acme.semantic.pgwire;

import java.sql.Types;
import java.util.Locale;

public final class PgTypeMapper {
  private PgTypeMapper() {}

  public static int oid(String type) {
    if (type == null) return 1043;
    String t = type.toLowerCase(Locale.ROOT);
    if (t.startsWith("uuid")) return 2950;
    if (t.startsWith("jsonb")) return 3802;
    if (t.startsWith("json")) return 114;
    if (t.startsWith("bytea") || t.startsWith("binary") || t.startsWith("varbinary")) return 17;
    if (t.startsWith("bigint")) return 20;
    if (t.startsWith("smallint")) return 21;
    if (t.startsWith("integer") || t.equals("int") || t.startsWith("int(")) return 23;
    if (t.startsWith("decimal") || t.startsWith("numeric")) return 1700;
    if (t.startsWith("double")) return 701;
    if (t.startsWith("real")) return 700;
    if (t.startsWith("boolean")) return 16;
    if (t.startsWith("date")) return 1082;
    if (t.startsWith("timestamp with time zone") || t.startsWith("timestamptz")) return 1184;
    if (t.startsWith("timestamp")) return 1114;
    if (t.startsWith("time with time zone") || t.startsWith("timetz")) return 1266;
    if (t.startsWith("time")) return 1083;
    return 1043;
  }

  public static int size(int oid) {
    return switch (oid) {
      case 16 -> 1;
      case 20, 701, 1083, 1114, 1184 -> 8;
      case 21 -> 2;
      case 23, 700, 1082 -> 4;
      case 2950 -> 16;
      default -> -1;
    };
  }

  public static int jdbcType(String type) {
    return switch (oid(type)) {
      case 16 -> Types.BOOLEAN;
      case 20 -> Types.BIGINT;
      case 21 -> Types.SMALLINT;
      case 23 -> Types.INTEGER;
      case 700 -> Types.REAL;
      case 701 -> Types.DOUBLE;
      case 1700 -> Types.NUMERIC;
      case 1082 -> Types.DATE;
      case 1114 -> Types.TIMESTAMP;
      case 1184 -> Types.TIMESTAMP_WITH_TIMEZONE;
      case 1083 -> Types.TIME;
      case 1266 -> Types.TIME_WITH_TIMEZONE;
      case 17 -> Types.VARBINARY;
      case 2950 -> Types.OTHER;
      default -> Types.VARCHAR;
    };
  }
}
