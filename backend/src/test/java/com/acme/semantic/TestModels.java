package com.acme.semantic;

import com.acme.semantic.model.*;
import java.nio.file.*;
import java.util.*;

public final class TestModels {
  private TestModels() {}

  public static SemanticModel demo() {
    try {
      Path root = Path.of("semantic-model");
      Map<String, String> files = new TreeMap<>();
      try (var s = Files.walk(root)) {
        for (Path p :
            s.filter(Files::isRegularFile)
                .filter(x -> x.toString().endsWith(".yaml") || x.toString().endsWith(".yml"))
                .toList()) files.put(root.relativize(p).toString(), Files.readString(p));
      }
      return new ModelParser().parse(new ModelRevision("test", files));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
