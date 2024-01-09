// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2023 MariaDB Corporation Ab
// Copyright (c) 2021-2023 SingleStore, Inc.

package com.singlestore.jdbc.unit.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.singlestore.jdbc.util.ClientParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@SuppressWarnings("ConstantConditions")
public class ClientParserTest {

  private void parse(
      String sql, String[] expected, String[] expectedNoBackSlash, boolean isInsertDuplicate) {
    ClientParser parser = ClientParser.parameterParts(sql, false);
    assertEquals(expected.length, parser.getParamCount() + 1, displayErr(parser, expected));

    int pos = 0;
    int paramPos = parser.getQuery().length;
    for (int i = 0; i < parser.getParamCount(); i++) {
      paramPos = parser.getParamPositions().get(i);
      assertEquals(expected[i], new String(parser.getQuery(), pos, paramPos - pos));
      pos = paramPos + 1;
    }
    assertEquals(expected[expected.length - 1], new String(parser.getQuery(), pos, paramPos - pos));

    parser = ClientParser.parameterParts(sql, true);
    assertEquals(
        expectedNoBackSlash.length, parser.getParamCount() + 1, displayErr(parser, expected));
    pos = 0;
    paramPos = parser.getQuery().length;
    for (int i = 0; i < parser.getParamCount(); i++) {
      paramPos = parser.getParamPositions().get(i);
      assertEquals(expectedNoBackSlash[i], new String(parser.getQuery(), pos, paramPos - pos));
      pos = paramPos + 1;
    }
    assertEquals(
        expectedNoBackSlash[expectedNoBackSlash.length - 1],
        new String(parser.getQuery(), pos, paramPos - pos));
    assertEquals(isInsertDuplicate, parser.isInsertDuplicate());
  }

  private String displayErr(ClientParser parser, String[] exp) {
    StringBuilder sb = new StringBuilder();
    sb.append("is:\n");

    int pos = 0;
    int paramPos = parser.getQuery().length;
    for (int i = 0; i < parser.getParamCount(); i++) {
      paramPos = parser.getParamPositions().get(i);
      sb.append(new String(parser.getQuery(), pos, paramPos - pos, StandardCharsets.UTF_8))
          .append("\n");
      pos = paramPos + 1;
    }
    sb.append(new String(parser.getQuery(), pos, paramPos - pos));

    sb.append("but was:\n");
    for (String s : exp) {
      sb.append(s).append("\n");
    }
    return sb.toString();
  }

  @Test
  public void ClientParser() {
    parse(
        "SELECT '\\\\test' /*test* #/ ;`*/",
        new String[] {"SELECT '\\\\test' /*test* #/ ;`*/"},
        new String[] {"SELECT '\\\\test' /*test* #/ ;`*/"},
        false);
    parse(
        "DO '\\\"', \"\\'\"",
        new String[] {"DO '\\\"', \"\\'\""},
        new String[] {"DO '\\\"', \"\\'\""},
        false);
    parse(
        "INSERT INTO TABLE(id,val) VALUES (1,2)",
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2)"},
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2)"},
        false);
    parse(
        "INSERT INTO TABLE(id,val) VALUES (1,2) ON DUPLICATE KEY UPDATE",
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2) ON DUPLICATE KEY UPDATE"},
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2) ON DUPLICATE KEY UPDATE"},
        true);
    parse(
        "INSERT INTO TABLE(id,val) VALUES (1,2) ON DUPLICATE",
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2) ON DUPLICATE"},
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2) ON DUPLICATE"},
        false);
    parse(
        "INSERT INTO TABLE(id,val) VALUES (1,2) ONDUPLICATE",
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2) ONDUPLICATE"},
        new String[] {"INSERT INTO TABLE(id,val) VALUES (1,2) ONDUPLICATE"},
        false);
  }

  @Test
  public void ClientParserInsertFlag() {
    assertFalse(ClientParser.parameterParts("WRONG INSERT_COMMAND", true).isInsert());
    assertFalse(ClientParser.parameterParts("INSERT_COMMAND WRONG ", true).isInsert());
    assertFalse(ClientParser.parameterParts("WRONGINSERT COMMAND", true).isInsert());
    assertFalse(ClientParser.parameterParts("WRONG INSERT", true).isInsert());
    assertFalse(ClientParser.parameterParts("WRONG small insert", true).isInsert());
    assertFalse(ClientParser.parameterParts("INSERT DUPLICATE", true).isInsertDuplicate());
    assertFalse(ClientParser.parameterParts("INSERT duplicate", true).isInsertDuplicate());
    assertFalse(ClientParser.parameterParts("INSERT _duplicate key", true).isInsertDuplicate());
    assertFalse(ClientParser.parameterParts("INSERT duplicate_ key", true).isInsertDuplicate());
  }
}
