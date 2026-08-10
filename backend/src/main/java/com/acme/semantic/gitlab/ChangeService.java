package com.acme.semantic.gitlab;

import com.acme.semantic.model.*;
import com.acme.semantic.validation.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ChangeService {
  private static final int MAX_CHANGE_BYTES = 2 * 1024 * 1024;
  private final ModelRepository repository;
  private final ModelParser parser;
  private final ModelValidator validator;

  public ChangeService(ModelRepository repository, ModelParser parser, ModelValidator validator) {
    this.repository = repository;
    this.parser = parser;
    this.validator = validator;
  }

  public Preview validate(ChangeSet change) {
    int size =
        change.files().entrySet().stream()
                .mapToInt(e -> e.getKey().length() + e.getValue().length())
                .sum()
            + change.deletions().stream().mapToInt(String::length).sum();
    if (size > MAX_CHANGE_BYTES) throw new IllegalArgumentException("Change set exceeds 2 MiB");
    ModelRevision base = repository.loadDefaultRevision();
    Map<String, String> merged = new TreeMap<>(base.files());
    change
        .deletions()
        .forEach(
            path -> {
              safePath(path);
              if (change.files().containsKey(path))
                throw new IllegalArgumentException(
                    "A path cannot be both updated and deleted: " + path);
              if (!merged.containsKey(path))
                throw new IllegalArgumentException(
                    "Cannot delete a model file that does not exist: " + path);
              merged.remove(path);
            });
    change
        .files()
        .forEach(
            (path, content) -> {
              safePath(path);
              merged.put(path, content);
            });
    Set<String> changedPaths = new LinkedHashSet<>(change.files().keySet());
    changedPaths.addAll(change.deletions());
    try {
      SemanticModel candidate = parser.parse(new ModelRevision(base.revision(), merged));
      ValidationResult validation = validator.validate(candidate);
      return new Preview(
          base.revision(),
          validation,
          diff(base.files(), change.files(), change.deletions()),
          affected(changedPaths, "/objects/"),
          affected(changedPaths, "/metrics/"));
    } catch (ModelParseException e) {
      return new Preview(
          base.revision(),
          ValidationResult.of(
              List.of(
                  new ValidationError(
                      e.file(),
                      e.path(),
                      e.code(),
                      e.getMessage(),
                      ValidationError.Severity.ERROR))),
          diff(base.files(), change.files(), change.deletions()),
          affected(changedPaths, "/objects/"),
          affected(changedPaths, "/metrics/"));
    }
  }

  public ChangeResult submit(ChangeSet change) {
    Preview preview = validate(change);
    if (!preview.validation().valid()) throw new IllegalArgumentException("Change set is invalid");
    ChangeSet withBase =
        new ChangeSet(
            change.files(),
            change.deletions(),
            change.title(),
            change.description(),
            change.commitMessage(),
            change.baseRevision() == null ? preview.baseRevision() : change.baseRevision());
    if (!Objects.equals(withBase.baseRevision(), preview.baseRevision()))
      throw new RevisionConflictException("Default branch changed; rebuild the diff");
    return repository.createMergeRequest(withBase);
  }

  private void safePath(String path) {
    if (path.startsWith("/") || path.contains("..") || !path.matches("[A-Za-z0-9_./-]+\\.ya?ml"))
      throw new IllegalArgumentException("Unsafe YAML path: " + path);
  }

  private String diff(
      Map<String, String> base, Map<String, String> changes, Set<String> deletions) {
    StringBuilder out = new StringBuilder();
    changes.forEach(
        (path, current) -> {
          String previous = base.get(path);
          if (Objects.equals(previous, current)) return;
          out.append("--- a/").append(path).append("\n+++ b/").append(path).append("\n");
          if (previous == null)
            Arrays.stream(current.split("\\R", -1))
                .forEach(line -> out.append('+').append(line).append('\n'));
          else {
            Arrays.stream(previous.split("\\R", -1))
                .forEach(line -> out.append('-').append(line).append('\n'));
            Arrays.stream(current.split("\\R", -1))
                .forEach(line -> out.append('+').append(line).append('\n'));
          }
        });
    deletions.forEach(
        path -> {
          String previous = base.get(path);
          if (previous == null) return;
          out.append("--- a/").append(path).append("\n+++ /dev/null\n");
          Arrays.stream(previous.split("\\R", -1))
              .forEach(line -> out.append('-').append(line).append('\n'));
        });
    return out.toString();
  }

  private List<String> affected(Set<String> paths, String kind) {
    return paths.stream()
        .filter(path -> ("/" + path).contains(kind))
        .map(
            path -> {
              String file = path.substring(path.lastIndexOf('/') + 1);
              return file.replaceFirst("\\.ya?ml$", "");
            })
        .distinct()
        .sorted()
        .toList();
  }

  public record Preview(
      String baseRevision,
      ValidationResult validation,
      String diff,
      List<String> affectedObjects,
      List<String> affectedMetrics) {}
}
