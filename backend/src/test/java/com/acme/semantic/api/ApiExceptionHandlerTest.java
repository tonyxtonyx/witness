package com.acme.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class ApiExceptionHandlerTest {
  @Test
  void exposesNotImplementedReasonForLocalMergeRequestSubmission() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/changes/submit");
    ResponseStatusException failure =
        new ResponseStatusException(
            HttpStatus.NOT_IMPLEMENTED,
            "Merge-request submission requires GitLab mode; use direct CRUD endpoints instead");

    var response = new ApiExceptionHandler().error(failure, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    assertThat(response.getBody().message()).contains("requires GitLab mode");
  }
}
