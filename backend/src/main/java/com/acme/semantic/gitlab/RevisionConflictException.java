package com.acme.semantic.gitlab;

public class RevisionConflictException extends RuntimeException {
  public RevisionConflictException(String message) {
    super(message);
  }
}
