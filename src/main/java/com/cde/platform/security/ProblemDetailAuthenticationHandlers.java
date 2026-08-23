package com.cde.platform.security;

import com.cde.platform.exception.ApiProblem;
import com.cde.platform.openapi.ApiDocumentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    @Bean
    public AccessDeniedHandler problemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> write(objectMapper, request, response,
            HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
            // Deliberately says nothing about which permission is missing, or
            // whether the thing being addressed exists. Naming either turns a
            // refusal into a source of information about the system.
            "You do not have permission to do that.");
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
