package com.cde.platform.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates uncaught exceptions into responses that say what actually went
 * wrong.
 *
 * Without this, anything escaping a controller propagates past Spring
 * Security's ExceptionTranslationFilter, which answers 403 — so a failed
 * database constraint reported itself as a permissions error and sent
 * debugging in entirely the wrong direction.
 *
 * Messages are deliberately generic. Details are logged server-side; the
 * client is told the category, never the stack trace or the SQL.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(
        DataIntegrityViolationException ex
    ) {
        log.error("Data integrity violation", ex);
        return build(HttpStatus.CONFLICT,
            "The request conflicts with existing data — the record may still be referenced elsewhere.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .findFirst()
            .orElse("Request validation failed.");
        return build(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ConverterOfflineException.class)
    public ResponseEntity<Map<String, Object>> handleConverterOffline(ConverterOfflineException ex) {
        // Logged at warn, not error: the service is down, the request was fine.
        log.warn("Converter unavailable: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE,
            "The document conversion service is unavailable. Please try again shortly.");
    }

    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<Map<String, Object>> handleProcessingFailure(DocumentProcessingException ex) {
        log.error("Document processing failed", ex);
        // This message is authored by us for the user, so it is safe to echo.
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Rejected request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "The request could not be processed as submitted.");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status",    status.value());
        body.put("error",     status.getReasonPhrase());
        body.put("message",   message);
        return ResponseEntity.status(status).body(body);
    }
}
