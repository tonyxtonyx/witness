package com.acme.semantic.gitlab;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.ModelRevision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "semantic.gitlab.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class LocalModelRepository implements ModelRepository, MutableModelRepository {
  private final Path root;

  public LocalModelRepository(SemanticProperties properties) {
    root = Path.of(properties.modelPath()).toAbsolutePath().normalize();
  }

  @Override
  public ModelRevision loadDefaultRevision() {
    Map<String, String> files = new TreeMap<>();
    try (var stream = Files.walk(root)) {
      stream
          .filter(
              p ->
                  Files.isRegularFile(p)
                      && (p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")))
          .forEach(
              p -> {
                try {
                  files.put(root.relativize(p).toString(), Files.readString(p));
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load model directory " + root, e);
    }
    return new ModelRevision(sha(files), files);
  }

  @Override
  public ChangeResult createMergeRequest(ChangeSet changeSet) {
    String id = UUID.randomUUID().toString().substring(0, 8);
    String slug =
        changeSet
            .title()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    String branch =
        "semantic/change/"
            + (slug.isBlank() ? "model" : slug.substring(0, Math.min(slug.length(), 36)))
            + "-"
            + id;
    Map<String, String> commitFiles = new TreeMap<>(changeSet.files());
    changeSet.deletions().forEach(path -> commitFiles.put(path, "<deleted>"));
    String commit = sha(commitFiles);
    return new ChangeResult(
        branch, commit, new ChangeResult.MergeRequest(0, "mock://gitlab/merge_requests/" + id));
  }

  @Override
  public synchronized void apply(Map<String, String> upserts, Set<String> deletions) {
    try {
      for (String relative : deletions) Files.deleteIfExists(resolve(relative));
      for (var entry : upserts.entrySet()) {
        Path target = resolve(entry.getKey());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".semantic-", ".yaml");
        Files.writeString(temporary, entry.getValue(), StandardCharsets.UTF_8);
        try {
          Files.move(
              temporary,
              target,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot persist semantic model change", e);
    }
  }

  private Path resolve(String relative) {
    if (relative == null
        || relative.startsWith("/")
        || relative.contains("..")
        || !relative.matches("[A-Za-z0-9_./-]+\\.ya?ml"))
      throw new IllegalArgumentException("Unsafe model path: " + relative);
    Path target = root.resolve(relative).normalize();
    if (!target.startsWith(root))
      throw new IllegalArgumentException("Unsafe model path: " + relative);
    return target;
  }

  private String sha(Map<String, String> files) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      files.forEach(
          (k, v) -> {
            digest.update(k.getBytes(StandardCharsets.UTF_8));
            digest.update(v.getBytes(StandardCharsets.UTF_8));
          });
      return HexFormat.of().formatHex(digest.digest()).substring(0, 40);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
