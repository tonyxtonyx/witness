package com.acme.semantic.model;

public class ModelParseException extends RuntimeException {
  private final String file, path, code;

  public ModelParseException(String file, String path, String code, String message) {
    super(message);
    this.file = file;
    this.path = path;
    this.code = code;
  }

  public String file() {
    return file;
  }

  public String path() {
    return path;
  }

  public String code() {
    return code;
  }
}
