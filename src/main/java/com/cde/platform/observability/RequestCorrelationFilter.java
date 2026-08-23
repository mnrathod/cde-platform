package com.cde.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a trace identifier, so an error the user reports can be
 * found in the logs.
 *
 * <p>The identifier reaches the caller three ways: the {@code X-Trace-Id}
 * response header, the {@code traceId} member of every error body (§3.4), and
 * every log line emitted while the request is in flight. A user quoting the
 * identifier from an error message is quoting something that leads directly to
 * the request that failed.
 *
 * <p>An inbound W3C {@code traceparent} is adopted rather than replaced, so
 * the identifier already agrees with the tracing header the platform emits
 * today and will continue to agree when OpenTelemetry propagation is wired in
 * (§8.5). Nothing about the identifier is trusted for a security decision — it
 * only labels log lines — so accepting a client-supplied value is safe, but it
 * is still validated against the header format so a caller cannot inject
 * arbitrary text into the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    /** The name under which the identifier appears in every log line. */
    public static final String MDC_KEY = "traceId";

    /** Request attribute, so the exception handler can read it without MDC. */
    public static final String REQUEST_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".traceId";

    public static final String RESPONSE_HEADER = "X-Trace-Id";

    private static final String TRACEPARENT_HEADER = "traceparent";

    /** version "-" 32 hex trace-id "-" 16 hex parent-id "-" 2 hex flags. */
    private static final Pattern TRACEPARENT =
        Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    /** An all-zero trace-id is invalid per the W3C specification. */
    private static final String INVALID_TRACE_ID = "0".repeat(32);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = adoptInboundTraceId(request).orElseGet(RequestCorrelationFilter::generateTraceId);

        MDC.put(MDC_KEY, traceId);
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled. A key left behind would relabel the next
            // request served by this thread with the previous request's id,
            // which is worse than having no identifier at all.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Reads the trace identifier this request already carries, if it carries a
     * well-formed one.
     */
    private java.util.Optional<String> adoptInboundTraceId(HttpServletRequest request) {
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        if (traceparent == null) {
            return java.util.Optional.empty();
        }
        var matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }
        String traceId = matcher.group(1);
        return INVALID_TRACE_ID.equals(traceId) ? java.util.Optional.empty() : java.util.Optional.of(traceId);
    }

    /**
     * A 32-character hexadecimal identifier, the same shape a W3C trace-id
     * takes, so the format does not change once real propagation arrives.
     */
    private static String generateTraceId() {
        UUID random = UUID.randomUUID();
        return "%016x%016x".formatted(random.getMostSignificantBits(), random.getLeastSignificantBits());
    }

    /**
     * The identifier for the request being served, or a placeholder when
     * called outside a request.
     */
    public static String currentTraceId(HttpServletRequest request) {
        Object attribute = request != null ? request.getAttribute(REQUEST_ATTRIBUTE) : null;
        if (attribute instanceof String traceId) {
            return traceId;
        }
        String fromMdc = MDC.get(MDC_KEY);
        return fromMdc != null ? fromMdc : generateTraceId();
    }
}
