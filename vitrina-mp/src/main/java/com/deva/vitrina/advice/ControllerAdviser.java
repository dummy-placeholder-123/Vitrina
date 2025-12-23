package com.deva.vitrina.advice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.deva.vitrina.exception.IdempotencyKeyConflictException;

// Centralizes exception handling for all REST controllers, keeping error responses consistent.
@RestControllerAdvice
public class ControllerAdviser {

    // Logger replaces printStackTrace with structured, production-friendly logging.
    private static final Logger log = LoggerFactory.getLogger(ControllerAdviser.class);

    // Handles validation failures and reports field-level errors to callers.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a,
                        LinkedHashMap::new));
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, "Validation failed", request.getRequestURI(),
                fieldErrors);
        log.warn("Validation error on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // Handles malformed JSON or unreadable request bodies early with a clear 400 response.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, "Malformed request body", request.getRequestURI(),
                null);
        log.warn("Unreadable request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // Handles database constraint violations (e.g., unique keys) as conflict responses.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request
    ) {
        ApiError body = ApiError.of(HttpStatus.CONFLICT, "Data integrity violation", request.getRequestURI(),
                null);
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // Reports idempotency key reuse conflicts as a 409.
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyKeyConflictException ex,
            HttpServletRequest request
    ) {
        ApiError body = ApiError.of(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI(), null);
        log.warn("Idempotency conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // Maps not-found errors to a 404 response instead of leaking stack traces.
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            EntityNotFoundException ex,
            HttpServletRequest request
    ) {
        ApiError body = ApiError.of(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), null);
        log.warn("Not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // Treats illegal arguments as client errors with a 400 response.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), null);
        log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // Last-resort handler to avoid leaking internal details; returns a generic 500.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(
            Exception ex,
            HttpServletRequest request
    ) {
        ApiError body = ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error",
                request.getRequestURI(), null);
        log.error("Unhandled error on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // Simple error envelope to keep the API response shape stable for clients.
    static class ApiError {
        private final Instant timestamp;
        private final int status;
        private final String error;
        private final String message;
        private final String path;
        private final Map<String, String> fieldErrors;

        private ApiError(Instant timestamp, int status, String error, String message, String path,
                Map<String, String> fieldErrors) {
            this.timestamp = timestamp;
            this.status = status;
            this.error = error;
            this.message = message;
            this.path = path;
            this.fieldErrors = fieldErrors;
        }

        static ApiError of(HttpStatus status, String message, String path, Map<String, String> fieldErrors) {
            return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public int getStatus() {
            return status;
        }

        public String getError() {
            return error;
        }

        public String getMessage() {
            return message;
        }

        public String getPath() {
            return path;
        }

        public Map<String, String> getFieldErrors() {
            return fieldErrors;
        }
    }
}
