package org.jdkxx.trader.app.web;

import java.util.Map;
import java.util.concurrent.CompletionException;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps gateway-layer exceptions to meaningful HTTP statuses: not connected 503, timeout 504,
 * rejected by the broker 502, bad parameter 400.
 *
 * <p>Every error body is {@code {code, message}} without a stack trace. Exceptions not handled here
 * fall through to Spring Boot's default error handling, which does not include stack traces or
 * messages by default.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, "PARAM_INVALID", e.getMessage());
  }

  /** State conflict (gateway not enabled, a job is already running, pool is full). */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
    return body(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage());
  }

  @ExceptionHandler(java.util.NoSuchElementException.class)
  public ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
    return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
  }

  /**
   * Timed out waiting for a gateway response. {@code POST /api/account/holdings/sync} is the only
   * endpoint that declares {@code throws Exception}; it can throw {@link
   * java.util.concurrent.TimeoutException}, and originally no handler in this class caught it (it
   * is neither IllegalArgument/IllegalState/NoSuchElement nor
   * GatewayException/CompletionException). The result was a 500 with Spring's default error body
   * instead of the 504 + {code,message} that every other gateway timeout returns (found in the
   * 2026-09-25 full-project review).
   */
  @ExceptionHandler(java.util.concurrent.TimeoutException.class)
  public ResponseEntity<Map<String, Object>> timeout(java.util.concurrent.TimeoutException e) {
    return body(
        HttpStatus.GATEWAY_TIMEOUT,
        "GATEWAY_TIMEOUT",
        e.getMessage() == null || e.getMessage().isBlank() ? "等待网关响应超时" : e.getMessage());
  }

  /**
   * Gateway exceptions, either thrown directly or delivered through a {@code CompletableFuture}
   * returned by a controller (Spring then wraps the failure in a {@link CompletionException}). The
   * cause is unwrapped once and mapped with the same rules as the dedicated handlers above, so an
   * async failure gets the same status as a synchronous one.
   */
  @ExceptionHandler({GatewayException.class, CompletionException.class})
  public ResponseEntity<Map<String, Object>> gateway(Exception e) {
    Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    if (c instanceof NotConnectedException) {
      return body(HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_NOT_CONNECTED", c.getMessage());
    }
    if (c instanceof RequestTimeoutException) {
      return body(HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT", c.getMessage());
    }
    if (c instanceof GatewayException g) {
      return body(HttpStatus.BAD_GATEWAY, "GATEWAY_REJECTED", g.getMessage());
    }
    if (c instanceof IllegalArgumentException) {
      return body(HttpStatus.BAD_REQUEST, "PARAM_INVALID", c.getMessage());
    }
    if (c instanceof IllegalStateException) {
      return body(HttpStatus.CONFLICT, "STATE_CONFLICT", c.getMessage());
    }
    if (c instanceof java.util.NoSuchElementException) {
      return body(HttpStatus.NOT_FOUND, "NOT_FOUND", c.getMessage());
    }
    if (c instanceof java.util.concurrent.TimeoutException) {
      return body(
          HttpStatus.GATEWAY_TIMEOUT,
          "GATEWAY_TIMEOUT",
          c.getMessage() == null || c.getMessage().isBlank() ? "等待网关响应超时" : c.getMessage());
    }
    // Anything else: log with the stack trace, return only the exception's toString (class +
    // message).
    log.error("未处理异常", c);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", c.toString());
  }

  /** {@code Map.of} rejects null values, hence the empty-string fallback for a null message. */
  private static ResponseEntity<Map<String, Object>> body(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "message", message == null ? "" : message));
  }
}
