package com.acme.semantic.auth;

public class AdminNotFoundException extends RuntimeException {
  public AdminNotFoundException(String message) {
    super(message);
  }
}
