package com.acme.semantic.cache;

public enum CacheKind {
  PLAN("plan"),
  DIMENSION_VALUES("dimension_values"),
  RESULT("result");

  private final String metricTag;

  CacheKind(String metricTag) {
    this.metricTag = metricTag;
  }

  public String metricTag() {
    return metricTag;
  }
}
