package com.acme.semantic.gitlab;

import com.acme.semantic.model.ModelRevision;

public interface ModelRepository {
  ModelRevision loadDefaultRevision();

  default String currentRevision() {
    return loadDefaultRevision().revision();
  }

  ChangeResult createMergeRequest(ChangeSet changeSet);
}
