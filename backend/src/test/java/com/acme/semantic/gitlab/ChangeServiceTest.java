package com.acme.semantic.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.ModelParser;
import com.acme.semantic.validation.DefaultModelValidator;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeServiceTest {
  @TempDir Path temporaryModel;

  @Test
  void validatesDeletionAndBuildsDeletedFileDiff() throws Exception {
    try (var files = Files.walk(Path.of("semantic-model"))) {
      for (Path source : files.filter(Files::isRegularFile).toList()) {
        Path target =
            temporaryModel.resolve(Path.of("semantic-model").relativize(source).toString());
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
      }
    }
    var properties = new SemanticProperties(temporaryModel.toString(), "test", null, null, null);
    var service =
        new ChangeService(
            new LocalModelRepository(properties), new ModelParser(), new DefaultModelValidator());
    var change =
        new ChangeSet(
            Map.of(),
            Set.of("metrics/average_order_value.yaml"),
            "Delete average order value",
            "Metric is obsolete",
            "chore(semantic): delete average order value",
            null);

    var preview = service.validate(change);

    assertThat(preview.validation().valid()).isTrue();
    assertThat(preview.diff()).contains("+++ /dev/null");
    assertThat(preview.affectedMetrics()).containsExactly("average_order_value");
    assertThat(service.submit(change).branch())
        .startsWith("semantic/change/delete-average-order-value-");
  }
}
