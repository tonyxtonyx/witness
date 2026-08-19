package com.acme.semantic.gitlab;

import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.model.ModelRevision;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "semantic.gitlab.enabled", havingValue = "true")
public class GitLabModelRepository implements ModelRepository {
  private final SemanticProperties.Gitlab config;
  private final RestClient client;

  @Autowired
  public GitLabModelRepository(SemanticProperties properties) {
    this(properties, requestBuilder(properties.gitlab()));
  }

  GitLabModelRepository(SemanticProperties properties, RestClient.Builder builder) {
    config = properties.gitlab();
    client =
        builder
            .baseUrl(config.baseUrl() + "/api/v4")
            .defaultHeader("PRIVATE-TOKEN", config.token())
            .build();
  }

  private static RestClient.Builder requestBuilder(SemanticProperties.Gitlab config) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(config.readTimeoutSeconds()));
    return RestClient.builder().requestFactory(requestFactory);
  }

  @SuppressWarnings("unchecked")
  @Override
  public String currentRevision() {
    Map<String, Object> branch =
        client
            .get()
            .uri(
                "/projects/{project}/repository/branches/{branch}",
                config.projectId(),
                config.defaultBranch())
            .retrieve()
            .body(Map.class);
    if (branch == null || !(branch.get("commit") instanceof Map<?, ?> commit)) {
      throw new IllegalStateException("GitLab branch response did not contain a commit");
    }
    return String.valueOf(commit.get("id"));
  }

  @SuppressWarnings("unchecked")
  @Override
  public ModelRevision loadDefaultRevision() {
    String sha = currentRevision();
    List<Map<String, Object>> tree = loadCompleteTree(sha);
    Map<String, String> files = new TreeMap<>();
    for (Map<String, Object> node : tree)
      if ("blob".equals(node.get("type"))
          && String.valueOf(node.get("path")).matches(".*\\.ya?ml$")) {
        String full = String.valueOf(node.get("path"));
        Map<String, Object> file =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/projects/{project}/repository/files/{file}")
                            .queryParam("ref", sha)
                            .build(config.projectId(), full))
                .retrieve()
                .body(Map.class);
        if (file == null || file.get("content") == null)
          throw new IllegalStateException("GitLab file response did not contain content: " + full);
        files.put(
            full.substring(config.modelPath().length() + 1),
            new String(
                Base64.getDecoder().decode(String.valueOf(file.get("content")).replace("\n", "")),
                StandardCharsets.UTF_8));
      }
    return new ModelRevision(sha, files);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> loadCompleteTree(String ref) {
    List<Map<String, Object>> tree = new ArrayList<>();
    int page = 1;
    while (true) {
      int requestedPage = page;
      ResponseEntity<List> response =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/projects/{project}/repository/tree")
                          .queryParam("path", config.modelPath())
                          .queryParam("ref", ref)
                          .queryParam("recursive", true)
                          .queryParam("per_page", 100)
                          .queryParam("page", requestedPage)
                          .build(config.projectId()))
              .retrieve()
              .toEntity(List.class);
      if (response.getBody() == null)
        throw new IllegalStateException("GitLab tree response did not contain a body");
      tree.addAll((List<Map<String, Object>>) response.getBody());
      String nextPage = response.getHeaders().getFirst("X-Next-Page");
      if (nextPage == null || nextPage.isBlank()) return tree;
      try {
        page = Integer.parseInt(nextPage);
      } catch (NumberFormatException e) {
        throw new IllegalStateException("Invalid GitLab X-Next-Page header: " + nextPage, e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public ChangeResult createMergeRequest(ChangeSet changeSet) {
    String latestRevision = currentRevision();
    if (!Objects.equals(latestRevision, changeSet.baseRevision()))
      throw new RevisionConflictException("Default branch changed; rebuild the diff");
    Set<String> existingPaths = new HashSet<>();
    for (Map<String, Object> node : loadCompleteTree(latestRevision)) {
      if (!"blob".equals(node.get("type"))) continue;
      String full = String.valueOf(node.get("path"));
      String prefix = config.modelPath() + "/";
      if (full.startsWith(prefix)) existingPaths.add(full.substring(prefix.length()));
    }
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String slug =
        changeSet
            .title()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    String branchName =
        "semantic/change/"
            + (slug.isBlank() ? "model" : slug.substring(0, Math.min(36, slug.length())))
            + "-"
            + suffix;
    client
        .post()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/projects/{project}/repository/branches")
                    .queryParam("branch", branchName)
                    .queryParam("ref", latestRevision)
                    .build(config.projectId()))
        .retrieve()
        .toBodilessEntity();
    List<Map<String, Object>> actions = new ArrayList<>();
    changeSet
        .files()
        .forEach(
            (path, content) ->
                actions.add(
                    Map.of(
                        "action",
                        existingPaths.contains(path) ? "update" : "create",
                        "file_path",
                        config.modelPath() + "/" + safePath(path),
                        "content",
                        content)));
    changeSet
        .deletions()
        .forEach(
            path ->
                actions.add(
                    Map.of(
                        "action",
                        "delete",
                        "file_path",
                        config.modelPath() + "/" + safePath(path))));
    Map<String, Object> commit =
        client
            .post()
            .uri("/projects/{project}/repository/commits", config.projectId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "branch",
                    branchName,
                    "commit_message",
                    changeSet.commitMessage(),
                    "actions",
                    actions))
            .retrieve()
            .body(Map.class);
    if (commit == null || commit.get("id") == null)
      throw new IllegalStateException("GitLab commit response did not contain an id");
    Map<String, Object> mr =
        client
            .post()
            .uri("/projects/{project}/merge_requests", config.projectId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "source_branch",
                    branchName,
                    "target_branch",
                    config.defaultBranch(),
                    "title",
                    changeSet.title(),
                    "description",
                    Objects.toString(changeSet.description(), "")))
            .retrieve()
            .body(Map.class);
    if (mr == null || !(mr.get("iid") instanceof Number iid) || mr.get("web_url") == null)
      throw new IllegalStateException("GitLab merge request response was incomplete");
    return new ChangeResult(
        branchName,
        String.valueOf(commit.get("id")),
        new ChangeResult.MergeRequest(
            iid.longValue(), String.valueOf(mr.get("web_url"))));
  }

  private String safePath(String value) {
    if (value.contains("..") || value.startsWith("/"))
      throw new IllegalArgumentException("Unsafe model path");
    return value;
  }
}
