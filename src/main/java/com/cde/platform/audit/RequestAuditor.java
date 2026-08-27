package com.cde.platform.audit;

import com.cde.platform.observability.RequestCorrelationFilter;
import com.cde.platform.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an audited event from inside a request, supplying the request's own
 * context so no caller has to remember to.
 *
 * <p>Two things it settles that would otherwise be settled inconsistently at
 * every call site.
 *
 * <p><strong>Where the source address comes from.</strong> Behind a reverse
 * proxy, {@code getRemoteAddr()} is the proxy. {@code X-Forwarded-For} is the
 * client — and is also a header the client sets, so a value read from it is a
 * value the client chose. The trail records the address the application
 * actually saw, and nothing else; see {@link #sourceAddressOf} for why.
 *
 * <p><strong>What happens when there is no tenant.</strong> A refused sign-in
 * for a username that matches no account belongs to no organisation. It cannot
 * be written to a tenant's trail without guessing which one, and guessing would
 * disclose across the boundary. Those events go to the application log for the
 * SIEM instead, which is where cross-tenant patterns — credential stuffing
 * across many organisations — are visible anyway.
 */
@Service
public class RequestAuditor {

    private static final Logger log = LoggerFactory.getLogger(RequestAuditor.class);

    /**
     * Long enough for an IPv6 address with an IPv4 suffix, which is the
     * longest form the column has to hold.
     */
    private static final int MAX_ADDRESS_LENGTH = 45;

    private final AuditTrailService auditTrail;

    public RequestAuditor(AuditTrailService auditTrail) {
        this.auditTrail = auditTrail;
    }

    /**
     * Records the event against the bound tenant, opening a transaction if the
     * caller has not.
     *
     * <p>{@code REQUIRED} rather than {@code MANDATORY} because the callers are
     * controllers, which are not themselves transactional. Where a change and
     * its audit record must commit together, call {@link AuditTrailService}
     * directly from inside the service that makes the change — that path
     * demands an existing transaction precisely so the two cannot separate.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AuditRequest.Builder builder, HttpServletRequest request) {
        auditTrail.record(withRequestContext(builder, request).build());
    }

    /**
     * Records an event that has no tenant to belong to.
     *
     * <p>Not silently dropped and not forced into a tenant: written to the
     * structured application log at WARN, with the same fields the trail would
     * have carried, so it reaches the SIEM and is searchable alongside
     * everything else.
     */
    public void recordWithoutTenant(AuditRequest.Builder builder, HttpServletRequest request) {
        AuditRequest event = withRequestContext(builder, request).build();
        log.warn("audit.untenanted action={} outcome={} actor={} source={} target={}/{}",
                 event.action(), event.outcome(), event.actorLabel(),
                 event.sourceIp(), event.targetType(), event.targetId());
    }

    /**
     * Records against the bound tenant when there is one, and to the log when
     * there is not — for call sites that cannot know which they are in.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordIfTenantBound(AuditRequest.Builder builder, HttpServletRequest request) {
        if (TenantContext.isSet()) {
            record(builder, request);
        } else {
            recordWithoutTenant(builder, request);
        }
    }

    private AuditRequest.Builder withRequestContext(AuditRequest.Builder builder,
                                                    HttpServletRequest request) {
        if (request == null) {
            return builder;
        }
        return builder
            .from(sourceAddressOf(request), request.getHeader("User-Agent"))
            .correlatedBy(traceIdOf(request));
    }

    /**
     * The address the application saw the request arrive from.
     *
     * <p>Deliberately {@code getRemoteAddr()} and not {@code X-Forwarded-For}.
     * That header is set by the client and only becomes trustworthy once a
     * proxy this deployment controls overwrites it — and this application does
     * not know whether it is behind such a proxy, so treating the header as the
     * source would let an attacker write any address they liked into the audit
     * trail. Attributing an attack to a forged address is worse than
     * attributing it to the proxy.
     *
     * <p>Resolving the real client address belongs in the web tier, by
     * configuring {@code server.forward-headers-strategy} once a trusted proxy
     * is actually in front — at which point {@code getRemoteAddr()} returns the
     * right thing here without this code changing.
     */
    private String sourceAddressOf(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null) {
            return null;
        }
        return address.length() <= MAX_ADDRESS_LENGTH
            ? address : address.substring(0, MAX_ADDRESS_LENGTH);
    }

    /**
     * Taken from the correlation filter rather than read out of the request
     * attributes here, so an audit record carries the same identifier the
     * error body and every log line for that request carry. Reading the
     * attribute directly would work until the filter changed where it puts it.
     */
    private String traceIdOf(HttpServletRequest request) {
        return RequestCorrelationFilter.currentTraceId(request);
    }
}
