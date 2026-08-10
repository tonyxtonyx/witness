package com.acme.semantic.gitlab;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.Set;

public record ChangeSet(
    Map<String, String> files,
    Set<String> deletions,
    @NotBlank String title,
    String description,
    @NotBlank String commitMessage,
    String baseRevision) {
  public ChangeSet {
    files = files == null ? Map.of() : Map.copyOf(files);
    deletions = deletions == null ? Set.of() : Set.copyOf(deletions);
  }

  @AssertTrue(message = "At least one file update or deletion is required")
  public boolean hasChanges() {
    return !files.isEmpty() || !deletions.isEmpty();
  }
}
