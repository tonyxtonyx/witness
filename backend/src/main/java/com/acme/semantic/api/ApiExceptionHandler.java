package com.acme.semantic.api;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.auth.AdminConflictException;
import com.acme.semantic.auth.AdminNotFoundException;
import com.acme.semantic.core.SemanticErrorCode;
import com.acme.semantic.core.SemanticException;
import com.acme.semantic.gitlab.RevisionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> error(Exception e, HttpServletRequest request) {
    HttpStatus status = status(e);
    boolean serverError = status.is5xxServerError();
    if (serverError)
      log.error(
          "Unhandled API error correlationId={} path={}",
          MDC.get("correlationId"),
          request.getRequestURI(),
          e);
    String message = message(e, status);
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

  private HttpStatus status(Exception e) {
    if (e instanceof AuthenticationService.InvalidCredentialsException
        || e instanceof AuthenticationService.InvalidRefreshTokenException)
      return HttpStatus.UNAUTHORIZED;
    if (e instanceof AdminConflictException || e instanceof RevisionConflictException)
      return HttpStatus.CONFLICT;
    if (e instanceof AdminNotFoundException) return HttpStatus.NOT_FOUND;
    if (e instanceof MethodArgumentTypeMismatchException
        || e instanceof MissingPathVariableException) return HttpStatus.BAD_REQUEST;
    if (e instanceof SemanticException semantic) {
      if (semantic.code() == SemanticErrorCode.ACCESS_DENIED) return HttpStatus.FORBIDDEN;
      if (semantic.code() == SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND)
        return HttpStatus.NOT_FOUND;
    }
    if (e instanceof ResponseStatusException response)
      return HttpStatus.valueOf(response.getStatusCode().value());
    if (e instanceof ErrorResponse response)
      return HttpStatus.valueOf(response.getStatusCode().value());
    if (e instanceof IllegalArgumentException) return HttpStatus.BAD_REQUEST;
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private String message(Exception e, HttpStatus status) {
    if (status == HttpStatus.UNAUTHORIZED) return "Invalid credentials";
    if (status == HttpStatus.FORBIDDEN) return "Access denied";
    if (e instanceof MethodArgumentTypeMismatchException
        || e instanceof MissingPathVariableException)
      return "Invalid or missing path parameter";
    if (e instanceof ResponseStatusException response) return response.getReason();
    if (e instanceof ErrorResponse) return status.getReasonPhrase();
    if (status.is5xxServerError()) return "Internal server error";
    return Objects.toString(e.getMessage(), status.getReasonPhrase());
  }

  public record ApiError(
      int status,
      String code,
      String message,
      String path,
      String correlationId,
      Instant timestamp) {}
}
