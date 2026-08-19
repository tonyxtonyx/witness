package com.acme.semantic.auth;

public class AdminConflictException extends RuntimeException {
  public AdminConflictException(String message) {
    super(message);
  }
}
