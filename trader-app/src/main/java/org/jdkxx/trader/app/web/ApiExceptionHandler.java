package org.jdkxx.trader.app.web;

import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * 把网关层异常映射成有意义的 HTTP 状态：未连接 503、超时 504、券商拒绝 502、参数错误 400。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, "PARAM_INVALID", e.getMessage());
    }

    /** 状态冲突（网关未启用、作业正在运行、池已满）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

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
        log.error("未处理异常", c);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", c.toString());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message == null ? "" : message));
    }
}
