package com.cde.platform.exception;

import com.cde.platform.observability.RequestCorrelationFilter;
import com.cde.platform.openapi.ApiDocumentation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

/**
 * Builds the single error envelope the API returns, in the RFC 9457 Problem
 * Details format (§3.4).
 *
 * <p>Every error reply — from any controller, for any status — has the same
 * shape, so a client writes one error path rather than one per endpoint. The
 * envelope carries a {@code traceId} the user can quote to support, which is
 * what makes a generic message actionable: the message says what to do next,
 * and the identifier lets someone else find out why.
 *
 * <p>{@code detail} is always text authored here for a human to read. Exception
 * messages, SQL, Java type names and stack traces never reach it — they are
 * logged instead. This is enforced by test rather than by convention.
 */
public final class ApiProblem {

    /**
     * Problem types are relative URIs, resolved against the API's own host and
     * described in the OpenAPI document. A type is a stable identifier a client
     * can branch on when the status code alone is too coarse — two different
     * 422s mean different things.
     */
    private static final String TYPE_PREFIX = "/problems/";

    private ApiProblem() {
    }

    /**
     * @param status what the caller should conclude about the request
     * @param type   stable slug identifying this kind of failure, e.g. {@code validation-failed}
     * @param title  short, unchanging summary of the problem type
     * @param detail what happened and what to do next, safe to show a user
     */
    public static ProblemDetail of(HttpStatus status,
                                   String type,
                                   String title,
                                   String detail,
                                   HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setTitle(title);
        problem.setProperty(ApiDocumentation.PROBLEM_TRACE_ID,
            RequestCorrelationFilter.currentTraceId(request));
        problem.setProperty(ApiDocumentation.PROBLEM_TIMESTAMP, Instant.now().toString());

        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        return problem;
    }
}
