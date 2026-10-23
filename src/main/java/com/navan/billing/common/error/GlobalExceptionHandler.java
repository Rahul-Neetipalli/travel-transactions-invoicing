package com.navan.billing.common.error;

import com.navan.billing.common.db.DbErrors;
import com.navan.billing.common.error.ApiException.TryAgainException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Renders every failure as RFC 9457 application/problem+json with a stable {@code code}:
 * our own ApiExceptions as they are, Spring's web errors with their real status, database lock
 * timeouts as a retryable 503, and anything else as a 500 with a trace id (never a stack trace).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, WebRequest request) {
        return problem(e.status(), e.code(), e.getMessage(), request, e.extensions(), e.headers());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body is missing or is not valid JSON", request, Map.of(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e, WebRequest request) {
        // Spring's own web errors (unknown route, wrong method, wrong content type, ...) keep their status.
        if (e instanceof ErrorResponse springError) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            return problem(status, status.name(), springError.getBody().getDetail(), request, Map.of(), null);
        }
        // Lock timeouts and deadlock victims wrote nothing: tell the client to retry instead of a 500.
        if (DbErrors.isRetryable(e)) {
            log.warn("Retryable database failure: {}", e.toString());
            return api(new TryAgainException(e), request);
        }
        String traceId = UUID.randomUUID().toString();
        log.error("Unexpected failure, trace_id={}", traceId, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Unexpected error", request, Map.of("trace_id", traceId), null);
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code, String detail, WebRequest request,
                                                                Map<String, Object> extensions, HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "/problems/" + code.toLowerCase().replace('_', '-'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getDescription(false).replaceFirst("^uri=", ""));
        body.put("code", code);
        body.putAll(extensions);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.body(body);
    }
}
