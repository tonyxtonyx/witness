package com.acme.semantic.pgwire;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import org.junit.jupiter.api.Test;

class PgTypeMapperTest {
  @Test
  void mapsTypes() {
    assertThat(PgTypeMapper.oid("decimal(18,2)")).isEqualTo(1700);
    assertThat(PgTypeMapper.jdbcType("timestamp")).isEqualTo(Types.TIMESTAMP);
  }
}
