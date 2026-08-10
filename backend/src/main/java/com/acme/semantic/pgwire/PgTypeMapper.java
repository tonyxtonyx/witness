package com.acme.semantic.pgwire;

import java.sql.Types;
import java.util.Locale;

public final class PgTypeMapper {
  private PgTypeMapper() {}

  public static int oid(String type) {
    String t = type.toLowerCase(Locale.ROOT);
    if (t.startsWith("bigint")) return 20;
    if (t.startsWith("integer") || t.startsWith("int")) return 23;
    if (t.startsWith("smallint")) return 21;
    if (t.startsWith("decimal") || t.startsWith("numeric")) return 1700;
    if (t.startsWith("double")) return 701;
    if (t.startsWith("real")) return 700;
    if (t.startsWith("boolean")) return 16;
    if (t.startsWith("date")) return 1082;
    if (t.startsWith("timestamp")) return 1114;
    return 1043;
  }

  public static int size(int oid) {
    return switch (oid) {
      case 16 -> 1;
      case 20, 701 -> 8;
      case 21 -> 2;
      case 23, 700, 1082 -> 4;
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
      default -> Types.VARCHAR;
    };
  }
}
