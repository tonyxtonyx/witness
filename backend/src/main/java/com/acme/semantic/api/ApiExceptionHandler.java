package com.acme.semantic.api;

import com.acme.semantic.gitlab.RevisionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> error(Exception e, HttpServletRequest request) {
    HttpStatus status =
        e instanceof ResponseStatusException response
            ? HttpStatus.valueOf(response.getStatusCode().value())
            : e instanceof RevisionConflictException
                ? HttpStatus.CONFLICT
                : e instanceof IllegalArgumentException
                    ? HttpStatus.BAD_REQUEST
                    : HttpStatus.INTERNAL_SERVER_ERROR;
    boolean serverError = status.is5xxServerError();
    if (serverError)
      log.error(
          "Unhandled API error correlationId={} path={}",
          MDC.get("correlationId"),
          request.getRequestURI(),
          e);
    String message =
        serverError
            ? "Internal server error"
            : e instanceof ResponseStatusException response
                ? response.getReason()
                : Objects.toString(e.getMessage(), status.getReasonPhrase());
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                status.value(),
                status.getReasonPhrase().toUpperCase().replace(' ', '_'),
                message,
                request.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now()));
  }

  public record ApiError(
      int status,
      String code,
      String message,
      String path,
      String correlationId,
      Instant timestamp) {}
}
