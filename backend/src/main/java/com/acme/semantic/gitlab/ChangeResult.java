package com.acme.semantic.gitlab;

public record ChangeResult(String branch, String commitSha, MergeRequest mergeRequest) {
  public record MergeRequest(long id, String url) {}
}
