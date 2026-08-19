package com.acme.semantic.catalog;

import com.acme.semantic.gitlab.ModelRepository;
import com.acme.semantic.model.*;
import com.acme.semantic.validation.*;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SemanticCatalog {
  private static final Logger log = LoggerFactory.getLogger(SemanticCatalog.class);
  private final ModelRepository repository;
  private final ModelParser parser;
  private final ModelValidator validator;
  private final AtomicReference<ActiveModel> active = new AtomicReference<>();
  private volatile Status status = new Status(false, null, null, null, "Not loaded");

  public SemanticCatalog(ModelRepository repository, ModelParser parser, ModelValidator validator) {
    this.repository = repository;
    this.parser = parser;
    this.validator = validator;
  }

  @PostConstruct
  public void init() {
    reload();
  }

  @Scheduled(fixedDelayString = "${semantic.gitlab.poll-ms:60000}")
  public void poll() {
    try {
      if (active.get() == null
          || !repository.currentRevision().equals(active.get().model().revision())) {
        reload();
      }
    } catch (Exception e) {
      status =
          new Status(
              false,
              activeRevision(),
              Instant.now(),
              null,
              "Model polling failed; previous revision remains active");
      log.warn("Semantic model polling failed; activeRevision={}", activeRevision(), e);
    }
  }

  public synchronized Status reload() {
    try {
      ModelRevision revision = repository.loadDefaultRevision();
      SemanticModel candidate = parser.parse(revision);
      ValidationResult result = validator.validate(candidate);
      if (!result.valid()) {
        status =
            new Status(
                false,
                activeRevision(),
                Instant.now(),
                result,
                "Candidate model is invalid; previous revision remains active");
        return status;
      }
      active.set(new ActiveModel(candidate, revision.files()));
      status = new Status(true, candidate.revision(), Instant.now(), result, "Model active");
    } catch (Exception e) {
      status =
          new Status(
              false,
              activeRevision(),
              Instant.now(),
              null,
              "Model reload failed; previous revision remains active");
      log.warn("Semantic model reload failed; activeRevision={}", activeRevision(), e);
    }
    return status;
  }

  public SemanticModel model() {
    ActiveModel current = active.get();
    if (current == null)
      throw new IllegalStateException("No valid semantic model loaded: " + status.message());
    return current.model();
  }

  public String source(String path) {
    ActiveModel current = active.get();
    if (current == null)
      throw new IllegalStateException("No valid semantic model loaded: " + status.message());
    return current.files().get(path);
  }

  public Status status() {
    return status;
  }

  private String activeRevision() {
    return active.get() == null ? null : active.get().model().revision();
  }

  private record ActiveModel(SemanticModel model, Map<String, String> files) {
    private ActiveModel {
      files = Map.copyOf(files);
    }
  }

  public record Status(
      boolean healthy,
      String activeRevision,
      Instant checkedAt,
      ValidationResult validation,
      String message) {}
}
