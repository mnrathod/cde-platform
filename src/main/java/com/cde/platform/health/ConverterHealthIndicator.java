package com.cde.platform.health;

import com.cde.platform.service.ConverterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Reports whether the CAD/Office converter is reachable.
 *
 * <p>The converter is a separate process. When it is down, DXF, DWG and Office
 * uploads cannot be converted — but PDFs still open, markup still saves, and
 * every existing document still loads. Reporting the service DOWN for that
 * would be wrong twice over: it pages someone for a partial loss of function,
 * and if a readiness probe believed it, Kubernetes would pull every healthy
 * pod out of rotation and turn a degraded feature into a total outage.
 *
 * <p>So this contributes {@code DEGRADED} rather than {@code DOWN}, and is
 * deliberately left out of the readiness group (see
 * {@code management.endpoint.health.group.readiness}). The aggregate stops
 * saying UP — which is what makes it worth alerting on — while still answering
 * 200, which is what keeps traffic flowing.
 *
 * <p>Registered as {@code converter} in {@code /actuator/health}.
 */
@Component("converter")
public class ConverterHealthIndicator implements HealthIndicator {

    /**
     * A dependency being unavailable is a normal operating state, not a
     * failure of this service.
     */
    static final Status DEGRADED = new Status("DEGRADED");

    private final ConverterService converterService;
    private final String converterUrl;

    public ConverterHealthIndicator(
            ConverterService converterService,
            @Value("${cde.converter.url:http://localhost:5001}") String converterUrl) {
        this.converterService = converterService;
        this.converterUrl = converterUrl;
    }

    @Override
    public Health health() {
        // Reuses the same probe the upload path uses, so health cannot report
        // something different from what an upload would actually find.
        boolean reachable = converterService.isConverterRunning();

        // The URL is configuration, not a secret, and naming it is the
        // difference between "the converter is down" and knowing which one was
        // being asked.
        Health.Builder builder = reachable ? Health.up() : Health.status(DEGRADED);
        return builder
                .withDetail("url", converterUrl)
                .withDetail("affected", reachable
                        ? "none"
                        : "CAD and Office conversion; PDFs and markup are unaffected")
                .build();
    }
}
