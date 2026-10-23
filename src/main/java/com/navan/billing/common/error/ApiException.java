package com.navan.billing.common.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** Base for errors rendered as RFC 9457 problem+json with a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extensions = new LinkedHashMap<>();
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public ApiException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    public ApiException header(String name, String value) {
        headers.add(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public HttpHeaders headers() {
        return headers;
    }

    /** 400: the request is well-formed JSON but asks for something invalid. */
    public static class BadRequestException extends ApiException {

        public BadRequestException(String code, String detail) {
            super(HttpStatus.BAD_REQUEST, code, detail);
        }
    }

    /** 409: the request conflicts with stored state (duplicate, frozen, mixed currency). */
    public static class ConflictException extends ApiException {

        public ConflictException(String code, String detail) {
            super(HttpStatus.CONFLICT, code, detail);
        }
    }

    /** 404: the resource (or one of the referenced resources) does not exist. */
    public static class NotFoundException extends ApiException {

        public NotFoundException(String code, String detail) {
            super(HttpStatus.NOT_FOUND, code, detail);
        }

        public static NotFoundException of(String resource, String id) {
            return new NotFoundException("NOT_FOUND", resource + " '" + id + "' does not exist");
        }
    }

    /** 503: a temporary conflict (lock timeout, deadlock, a race whose winner vanished). Nothing was written; retry. */
    public static class TryAgainException extends ApiException {

        public TryAgainException(Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN", "Temporary conflict, nothing was saved; retry the request");
            initCause(cause);
            header("Retry-After", "1");
        }
    }

    /** 400 VALIDATION_FAILED, carrying every field error found, not just the first. */
    public static class ValidationFailedException extends ApiException {

        public ValidationFailedException(List<Violations.FieldError> errors) {
            super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed with " + errors.size() + " error(s)");
            with("errors", errors);
        }

        public static ValidationFailedException single(String pointer, String message) {
            return new ValidationFailedException(List.of(new Violations.FieldError(pointer, message)));
        }
    }
}
