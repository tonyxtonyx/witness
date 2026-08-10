package com.acme.semantic.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.acme.semantic.config.SemanticProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitLabModelRepositoryTest {
  @Test
  void followsTreePaginationAndEncodesGitLabPathVariablesOnce() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    var gitlab =
        new SemanticProperties.Gitlab(
            true,
            "https://gitlab.example",
            "group/project",
            "token",
            "main",
            "semantic-model",
            60_000,
            1,
            2);
    var properties = new SemanticProperties("semantic-model", "test", null, null, gitlab);

    server
        .expect(
            once(),
            requestTo(
                "https://gitlab.example/api/v4/projects/group%2Fproject/repository/branches/main"))
        .andRespond(withSuccess("{\"commit\":{\"id\":\"abc123\"}}", MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(Matchers.containsString("/repository/tree")))
        .andExpect(requestTo(Matchers.containsString("page=1")))
        .andRespond(
            withSuccess(
                    "[{\"type\":\"blob\",\"path\":\"semantic-model/project.yaml\"}]",
                    MediaType.APPLICATION_JSON)
                .header("X-Next-Page", "2"));
    server
        .expect(once(), requestTo(Matchers.containsString("/repository/tree")))
        .andExpect(requestTo(Matchers.containsString("page=2")))
        .andRespond(
            withSuccess(
                "[{\"type\":\"blob\",\"path\":\"semantic-model/objects/orders.yaml\"}]",
                MediaType.APPLICATION_JSON));
    expectFile(server, "semantic-model%2Fproject.yaml", "project");
    expectFile(server, "semantic-model%2Fobjects%2Forders.yaml", "orders");

    var revision = new GitLabModelRepository(properties, builder).loadDefaultRevision();

    assertThat(revision.revision()).isEqualTo("abc123");
    assertThat(revision.files())
        .containsEntry("project.yaml", "project")
        .containsEntry("objects/orders.yaml", "orders");
    server.verify();
  }

  private void expectFile(MockRestServiceServer server, String encodedPath, String content) {
    String encoded =
        Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    server
        .expect(once(), requestTo(Matchers.containsString("/repository/files/" + encodedPath)))
        .andRespond(
            withSuccess("{\"content\":\"" + encoded + "\"}", MediaType.APPLICATION_JSON));
  }
}
