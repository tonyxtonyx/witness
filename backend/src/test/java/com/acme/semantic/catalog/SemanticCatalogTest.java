package com.acme.semantic.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.semantic.cache.CacheKind;
import com.acme.semantic.cache.InProcessSemanticResultCache;
import com.acme.semantic.cache.SemanticCacheKey;
import com.acme.semantic.cache.SemanticCacheManager;
import com.acme.semantic.cache.SemanticCacheValues;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.gitlab.ChangeResult;
import com.acme.semantic.gitlab.ChangeSet;
import com.acme.semantic.gitlab.ModelRepository;
import com.acme.semantic.model.ModelParser;
import com.acme.semantic.model.ModelRevision;
import com.acme.semantic.validation.DefaultModelValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void successfulModelReloadDropsEntriesFromEveryRevision() throws Exception {
    ModelRevision first = demoRevision();
    ModelRevision second = new ModelRevision("git-main-2", first.files());
    AtomicReference<ModelRevision> revision = new AtomicReference<>(first);
    ModelRepository repository =
        new ModelRepository() {
          @Override
          public ModelRevision loadDefaultRevision() {
            return revision.get();
          }

          @Override
          public ChangeResult createMergeRequest(ChangeSet changeSet) {
            throw new UnsupportedOperationException();
          }
        };
    SemanticProperties.Cache config = SemanticProperties.Cache.defaults();
    InProcessSemanticResultCache backing =
        new InProcessSemanticResultCache(
            config, new SimpleMeterRegistry(), java.time.Clock.systemUTC());
    SemanticCacheManager cache = new SemanticCacheManager(backing, config);
    SemanticCatalog catalog =
        new SemanticCatalog(
            repository, new ModelParser(), new DefaultModelValidator(), cache);
    catalog.init();
    SemanticCacheKey key =
        new SemanticCacheKey(
            CacheKind.RESULT,
            "git-main-1",
            "SELECT 1",
            SemanticCacheValues.fingerprint(java.util.List.of()),
            SemanticCacheManager.emptyAuthorizationFingerprint(),
            "");
    backing.put(key, "cached", 16);
    assertThat(backing.size(CacheKind.RESULT)).isEqualTo(1);

    revision.set(second);
    SemanticCatalog.Status status = catalog.reload();

    assertThat(status.healthy()).isTrue();
    assertThat(status.activeRevision()).isEqualTo("git-main-2");
    assertThat(backing.size(CacheKind.RESULT)).isZero();
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
