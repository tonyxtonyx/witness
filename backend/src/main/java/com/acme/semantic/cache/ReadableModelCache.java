package com.acme.semantic.cache;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticAccessPolicy;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.model.SemanticModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded memoization of the pgwire/raw-SQL authorization view. */
@Component
public class ReadableModelCache {
  private static final int MAX_ENTRIES = 256;

  private final int maxEntries;
  private final LinkedHashMap<Key, View> entries = new LinkedHashMap<>(16, 0.75f, true);
  private long modelBuilds;
  private long fingerprintBuilds;

  @Autowired
  public ReadableModelCache(SemanticProperties properties) {
    this(
        Math.min(
            MAX_ENTRIES,
            Math.max(
                1,
                properties.cache() == null
                    ? SemanticProperties.Cache.defaults().maxEntries()
                    : properties.cache().maxEntries())));
  }

  public ReadableModelCache(int maxEntries) {
    this.maxEntries = Math.max(1, maxEntries);
  }

  public synchronized View resolve(
      SemanticModel model,
      SemanticPrincipal principal,
      SemanticAccessPolicy policy) {
    Key key =
        new Key(
            model.revision(),
            effectiveGrantsFingerprint(principal),
            policy.readableModelScope(principal));
    View cached = entries.get(key);
    if (cached != null) return cached;
    Map<String, SemanticModel.SemanticObject> objects = new LinkedHashMap<>();
    model.objects().forEach(
        (id, object) -> {
          if (policy.canReadObject(principal, model, object))
            objects.put(id, readableObject(model, principal, policy, object));
        });
    Map<String, SemanticModel.Metric> metrics = new LinkedHashMap<>();
    model.metrics().forEach(
        (id, metric) -> {
          SemanticModel.SemanticObject base =
              model.resolveObject(metric.spec().baseObject(), model.domain(metric)).value();
          if (policy.canReadMetric(principal, model, metric)
              && base != null
              && policy.canReadObject(principal, model, base)) metrics.put(id, metric);
        });
    modelBuilds++;
    View created =
        new View(
            new SemanticModel(
                model.project(), objects, metrics, model.revision(), model.loadedAt()),
            this::recordFingerprintBuild);
    entries.put(key, created);
    while (entries.size() > maxEntries) entries.remove(entries.keySet().iterator().next());
    return created;
  }

  public synchronized long modelBuildCount() {
    return modelBuilds;
  }

  public synchronized long fingerprintBuildCount() {
    return fingerprintBuilds;
  }

  public synchronized int size() {
    return entries.size();
  }

  private synchronized void recordFingerprintBuild() {
    fingerprintBuilds++;
  }

  private SemanticModel.SemanticObject readableObject(
      SemanticModel model,
      SemanticPrincipal principal,
      SemanticAccessPolicy policy,
      SemanticModel.SemanticObject object) {
    SemanticModel.ObjectSpec spec = object.spec();
    List<SemanticModel.Dimension> dimensions =
        spec.dimensions().stream()
            .filter(dimension -> policy.canReadDimension(principal, model, object, dimension))
            .toList();
    return new SemanticModel.SemanticObject(
        object.version(),
        object.kind(),
        object.metadata(),
        new SemanticModel.ObjectSpec(
            spec.source(), spec.primaryKey(), dimensions, spec.relationships()),
        object.file());
  }

  private String effectiveGrantsFingerprint(SemanticPrincipal principal) {
    List<Object> grants = new ArrayList<>();
    principal.domainPermissions().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                grants.add(
                    List.of(
                        entry.getKey(),
                        entry.getValue().stream().map(Enum::name).sorted().toList())));
    return SemanticCacheValues.fingerprint(
        List.of(principal.authenticated(), principal.admin(), grants));
  }

  private record Key(
      String modelRevision,
      String effectiveGrantsFingerprint,
      String policyPrincipalScope) {}

  public static final class View {
    private final SemanticModel model;
    private final Runnable onFingerprintBuild;
    private volatile String fingerprint;

    private View(SemanticModel model, Runnable onFingerprintBuild) {
      this.model = model;
      this.onFingerprintBuild = onFingerprintBuild;
    }

    public SemanticModel model() {
      return model;
    }

    public String fingerprint() {
      String current = fingerprint;
      if (current != null) return current;
      synchronized (this) {
        if (fingerprint == null) {
          fingerprint = SemanticCacheValues.readableModelFingerprint(model);
          onFingerprintBuild.run();
        }
        return fingerprint;
      }
    }
  }
}
