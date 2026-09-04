package com.cde.platform.exception;

import com.cde.platform.cde.domain.RevisionAlreadySupersededException;
import com.cde.platform.cde.domain.StateTransitionNotPermittedException;
import com.cde.platform.cde.domain.SuitabilityCodeNotValidInStateException;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.upload.UploadRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import com.cde.platform.conversion.ConversionQueueFullException;
import com.cde.platform.fetch.FetchNotPermittedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates uncaught exceptions into responses that say what actually went
 * wrong.
 *
 * Without this, anything escaping a controller propagates past Spring
 * Security's ExceptionTranslationFilter, which answers 403 — so a failed
 * database constraint reported itself as a permissions error and sent
 * debugging in entirely the wrong direction.
 *
 * Messages are deliberately generic. Details are logged server-side against
 * the request's traceId; the client is told the category and given the
 * identifier, never the stack trace or the SQL.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                      HttpServletRequest request) {
        log.error("Data integrity violation", ex);
        return ApiProblem.of(HttpStatus.CONFLICT, "conflict", "Conflict",
            "The request conflicts with existing data — the record may still be referenced elsewhere.",
            request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletRequest request) {
        // Field errors are the caller's own input, and naming the offending
        // field is the whole point of a validation reply — it is what tells
        // them which box to fix. The message text comes from our own
        // constraint annotations, not from the parser.
        List<InvalidField> fields = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
            .toList();

        String detail = fields.isEmpty()
            ? "Request validation failed."
            : fields.size() + (fields.size() == 1 ? " field is invalid." : " fields are invalid.");

        ProblemDetail problem = ApiProblem.of(HttpStatus.UNPROCESSABLE_ENTITY,
            "validation-failed", "Validation failed", detail, request);
        problem.setProperty(ApiDocumentation.PROBLEM_INVALID_FIELDS, fields);
        return problem;
    }

    @ExceptionHandler(ConverterOfflineException.class)
    public ProblemDetail handleConverterOffline(ConverterOfflineException ex,
                                                HttpServletRequest request) {
        // Logged at warn, not error: the service is down, the request was fine.
        log.warn("Converter unavailable: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.SERVICE_UNAVAILABLE,
            "conversion-service-unavailable", "Conversion service unavailable",
            "The document conversion service is unavailable. Please try again shortly.",
            request);
    }

    @ExceptionHandler(DocumentProcessingException.class)
    public ProblemDetail handleProcessingFailure(DocumentProcessingException ex,
                                                 HttpServletRequest request) {
        log.error("Document processing failed", ex);
        // This message is authored by us for the user, so it is safe to echo.
        return ApiProblem.of(HttpStatus.UNPROCESSABLE_ENTITY,
            "document-processing-failed", "Document processing failed",
            ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex,
                                               HttpServletRequest request) {
        log.warn("Rejected request: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.BAD_REQUEST, "bad-request", "Bad request",
            "The request could not be processed as submitted.", request);
    }

    /**
     * Spring resolves the exceptions below itself, which forwards the response
     * to /error — and that dispatch used to be answered with an empty 403. A
     * wrong HTTP method, a malformed body and an unknown path were therefore
     * indistinguishable from a permissions failure, with no message to act on.
     * Handling them here keeps them out of the error dispatch entirely.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                  HttpServletRequest request) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.METHOD_NOT_ALLOWED,
            "method-not-allowed", "Method not allowed",
            "That HTTP method is not supported on this endpoint.", request);

        String[] supported = ex.getSupportedMethods();
        if (supported != null && supported.length > 0) {
            problem.setProperty("supportedMethods", List.of(supported));
        }
        return problem;
    }

    /**
     * A required query parameter or multipart part that was not sent.
     *
     * <p>Sibling of the two above and missed when they were written, so this
     * was the one framework rejection still leaving as Spring's default
     * {@code {timestamp, status, error, path}} — no {@code type}, no
     * {@code title}, no {@code traceId}. Every other error on the API is
     * RFC 9457, and the mobile SDKs parse that shape, so a client meeting
     * this one got a parse failure instead of the reason for the rejection.
     *
     * <p>The parameter's name is safe to return: the caller chose it, and
     * naming it is the difference between a fixable error and a guess.
     */
    @ExceptionHandler({
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class
    })
    public ProblemDetail handleMissingRequestPart(Exception ex, HttpServletRequest request) {
        String missing = ex instanceof org.springframework.web.bind.MissingServletRequestParameterException p
            ? p.getParameterName()
            : ((org.springframework.web.multipart.support.MissingServletRequestPartException) ex).getRequestPartName();

        ProblemDetail problem = ApiProblem.of(HttpStatus.BAD_REQUEST,
            "missing-request-parameter", "Missing request parameter",
            "The request is missing a required parameter. Add it and retry.", request);
        problem.setProperty("parameterName", missing);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex,
                                              HttpServletRequest request) {
        // The parser's own message names Java classes and enum constants, so
        // it is logged rather than returned.
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.BAD_REQUEST, "malformed-request-body", "Malformed request body",
            "The request body could not be read — check it is valid JSON and that each field holds an accepted value.",
            request);
    }

    /**
     * The CDE state machine refusing a move, and the two collisions that go
     * with it.
     *
     * <p>Each of these messages is authored in the domain for a person to read
     * — it names the states or the revision involved and nothing else — so it
     * is echoed rather than replaced with a generic line. They are not logged
     * as errors: being told that a published container cannot be edited is the
     * control working, not a fault.
     */
    @ExceptionHandler({
        StateTransitionNotPermittedException.class,
        SuitabilityCodeNotValidInStateException.class,
        RevisionAlreadySupersededException.class,
        ResourceConflictException.class })
    public ProblemDetail handleConflict(RuntimeException ex, HttpServletRequest request) {
        return ApiProblem.of(HttpStatus.CONFLICT, "state-conflict", "Conflict",
            ex.getMessage(), request);
    }

    /**
     * An upload refused on its own terms — a chunk index outside the declared
     * total, an oversized chunk, a file past the maximum.
     *
     * <p>Its message describes a limit the caller can see and act on, so it is
     * returned rather than replaced. {@code 422} rather than {@code 400}: the
     * request parsed perfectly well and was rejected on its content.
     */
    @ExceptionHandler(UploadRejectedException.class)
    public ProblemDetail handleUploadRejected(UploadRejectedException ex,
                                              HttpServletRequest request) {
        return ApiProblem.of(HttpStatus.UNPROCESSABLE_ENTITY,
            "upload-rejected", "Upload rejected", ex.getMessage(), request);
    }

    /**
     * A link an integrator supplied may not be fetched.
     *
     * <p>{@code 422} rather than {@code 400}: the request parsed perfectly
     * well and was refused on its content. The exception's message is returned
     * as written — it names the rule and says what to do about it, and it never
     * names the address a host resolved to, which is the whole reason those
     * messages are composed where the decision is made rather than here.
     */
    @ExceptionHandler(FetchNotPermittedException.class)
    public ProblemDetail handleFetchNotPermitted(FetchNotPermittedException ex,
                                                 HttpServletRequest request) {
        // Logged at warn, not error: a refused destination is this control
        // working. It is worth seeing — a run of them is someone probing — but
        // it is not a fault.
        log.warn("Refused to fetch an integrator-supplied URL: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.UNPROCESSABLE_ENTITY,
            "fetch-not-permitted", "Link not permitted", ex.getMessage(), request);
    }

    /**
     * The conversion queue is full.
     *
     * <p>{@code 429} with {@code Retry-After}, not {@code 503}: nothing is
     * broken and the caller should come back, which is precisely what those two
     * say together. Accepting the work instead would produce a job that sits at
     * PENDING until a restart fails it — which reads as a bug rather than as
     * back-pressure.
     */
    @ExceptionHandler(ConversionQueueFullException.class)
    public ResponseEntity<ProblemDetail> handleConversionQueueFull(
            ConversionQueueFullException ex, HttpServletRequest request) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.TOO_MANY_REQUESTS,
            "conversion-queue-full", "Too many conversions in progress",
            ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
            .body(problem);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex,
                                                HttpServletRequest request) {
        // Not logged as an error. Asking for something that is not there is an
        // ordinary outcome, and logging it at error level buries the failures
        // that do need attention.
        return ApiProblem.of(HttpStatus.NOT_FOUND, "not-found", "Not found",
            ex.getMessage(), request);
    }

    @ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
    public ProblemDetail handleNotFound(Exception ex, HttpServletRequest request) {
        log.warn("No handler: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.NOT_FOUND, "not-found", "Not found",
            "No such endpoint.", request);
    }

    /**
     * One invalid field, named so the client can attach the message to the
     * right control rather than showing it against the form as a whole.
     */
    public record InvalidField(String field, String message) {
    }
}
