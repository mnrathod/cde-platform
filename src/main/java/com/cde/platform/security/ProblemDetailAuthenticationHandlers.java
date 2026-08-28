package com.cde.platform.security;

import com.cde.platform.audit.AuditAction;
import com.cde.platform.audit.AuditOutcome;
import com.cde.platform.audit.AuditRequest;
import com.cde.platform.audit.AuditableChange;
import com.cde.platform.audit.RequestAuditor;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.openapi.ApiDocumentation;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Makes Spring Security's own rejections use the same error envelope as
 * everything else.
 *
 * <p>A request refused by the filter chain never reaches a controller, so the
 * {@code @RestControllerAdvice} that shapes every other error does not see it.
 * Without these, a {@code 401} and a {@code 403} came back in Boot's default
 * error format — no {@code type}, no {@code traceId}, and a {@code path} member
 * nothing else in the API uses. The specification documents both statuses as
 * problem documents, so a client that parsed one according to the specification
 * would have found nothing it expected in exactly the two cases it most needs
 * to handle.
 */
@Configuration
public class ProblemDetailAuthenticationHandlers {

    @Bean
    public AuthenticationEntryPoint problemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> write(objectMapper, request, response,
            HttpStatus.UNAUTHORIZED, "unauthenticated", "Not authenticated",
            "This endpoint requires a credential. Sign in and send the token as "
            + "`Authorization: Bearer <token>`.");
    }

    /**
     * Every refusal on authority is audited here.
     *
     * <p>This is the choke point: {@code @PreAuthorize} denials thrown inside a
     * controller and rule denials from the filter chain both arrive at the same
     * handler, so auditing here covers both without any endpoint remembering
     * to. An authenticated account being repeatedly refused is the clearest
     * signal there is that something is probing beyond its authority, and it is
     * the event most often absent from an audit trail.
     *
     * <p>The tenant context is still bound at this point — the JWT filter wraps
     * the exception translation filter — so the record reaches the right
     * organisation's trail. {@code recordIfTenantBound} covers the case where
     * it is not, rather than failing the response.
     */
    @Bean
    public AccessDeniedHandler problemDetailAccessDeniedHandler(ObjectMapper objectMapper,
                                                                RequestAuditor auditor) {
        return (request, response, accessDeniedException) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String actor = authentication == null
                ? AuditRequest.ANONYMOUS_ACTOR : authentication.getName();

            auditor.recordIfTenantBound(
                AuditRequest.byUnauthenticated(actor)
                    .did(AuditAction.AUTHORISATION_DENIED)
                    .outcome(AuditOutcome.DENIED)
                    // The path and method, because "denied" without them says
                    // only that something was refused. Both are bounded and
                    // neither is interpolated anywhere.
                    .changing(AuditableChange
                        .of("method", request.getMethod())
                        .and("path", request.getRequestURI())),
                request);

            write(objectMapper, request, response,
                HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                // Deliberately says nothing about which permission is missing,
                // or whether the thing being addressed exists. Naming either
                // turns a refusal into a source of information about the
                // system — the audit record carries the detail instead, on the
                // inside of the boundary.
                "You do not have permission to do that.");
        };
    }

    private void write(ObjectMapper objectMapper,
                       HttpServletRequest request,
                       HttpServletResponse response,
                       HttpStatus status,
                       String type,
                       String title,
                       String detail) throws IOException {
        ProblemDetail problem = ApiProblem.of(status, type, title, detail, request);

        response.setStatus(status.value());
        response.setContentType(ApiDocumentation.PROBLEM_MEDIA_TYPE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
