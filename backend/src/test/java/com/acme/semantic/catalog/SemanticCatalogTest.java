package com.acme.semantic.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.semantic.gitlab.ChangeResult;
import com.acme.semantic.gitlab.ChangeSet;
import com.acme.semantic.gitlab.ModelRepository;
import com.acme.semantic.model.ModelParser;
import com.acme.semantic.model.ModelRevision;
import com.acme.semantic.validation.DefaultModelValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SemanticCatalogTest {
  @Test
  void pollingRecoversWhenRemoteRepositoryBecomesAvailableAfterStartup() throws Exception {
    AtomicBoolean available = new AtomicBoolean(false);
    AtomicInteger loads = new AtomicInteger();
    ModelRevision revision = demoRevision();
    ModelRepository repository =
        new ModelRepository() {
          @Override
          public ModelRevision loadDefaultRevision() {
            loads.incrementAndGet();
            if (!available.get()) {
              throw new IllegalStateException("GitLab is still starting");
            }
            return revision;
          }

          @Override
          public ChangeResult createMergeRequest(ChangeSet changeSet) {
            throw new UnsupportedOperationException();
          }
        };
    SemanticCatalog catalog =
        new SemanticCatalog(repository, new ModelParser(), new DefaultModelValidator());

    catalog.init();
    assertThat(catalog.status().activeRevision()).isNull();

    available.set(true);
    catalog.poll();

    assertThat(catalog.status().healthy()).isTrue();
    assertThat(catalog.status().activeRevision()).isEqualTo("git-main-1");
    assertThat(catalog.model().objects()).containsKeys("retail.orders", "retail.customers");
    assertThat(catalog.source("project.yaml")).contains("witness_demo");
    assertThat(catalog.source("project.yaml")).contains("semanticSchema");
    assertThat(loads).hasValue(2);
  }

  private ModelRevision demoRevision() throws Exception {
    Path root = Path.of("semantic-model");
    Map<String, String> files = new TreeMap<>();
    try (var paths = Files.walk(root)) {
      for (Path path :
          paths
              .filter(Files::isRegularFile)
              .filter(file -> file.toString().endsWith(".yaml") || file.toString().endsWith(".yml"))
              .toList()) {
        files.put(root.relativize(path).toString(), Files.readString(path));
      }
    }
    return new ModelRevision("git-main-1", files);
  }
}
