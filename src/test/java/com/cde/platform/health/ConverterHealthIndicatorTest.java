package com.cde.platform.health;

import com.cde.platform.service.ConverterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A converter outage must not read as an application outage.
 *
 * <p>The distinction is the whole point of this indicator, and it is invisible
 * in normal running — it only shows up the day the converter stops, which is
 * the worst moment to discover the readiness probe disagrees.
 */
class ConverterHealthIndicatorTest {

    private static final String URL = "http://converter:5001";

    private ConverterHealthIndicator indicatorFor(boolean reachable) {
        ConverterService service = mock(ConverterService.class);
        when(service.isConverterRunning()).thenReturn(reachable);
        return new ConverterHealthIndicator(service, URL);
    }

    @Test
    @DisplayName("A reachable converter is UP, and says which one it reached")
    void reachableConverterIsUp() {
        Health health = indicatorFor(true).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(URL, health.getDetails().get("url"));
    }

    @Test
    @DisplayName("An unreachable converter is DEGRADED, never DOWN")
    void unreachableConverterIsDegradedNotDown() {
        Health health = indicatorFor(false).health();

        // DOWN would fail the aggregate health check. If a readiness probe
        // ever picked this component up, every pod would leave the load
        // balancer because one auxiliary process stopped — turning a lost
        // feature into a lost service.
        assertNotEquals(Status.DOWN, health.getStatus());
        assertEquals(ConverterHealthIndicator.DEGRADED, health.getStatus());
    }

    @Test
    @DisplayName("The outage says what is actually lost")
    void degradedStateExplainsTheImpact() {
        Health health = indicatorFor(false).health();

        // Whoever is woken by this needs to know it is not a full outage
        // before they decide how fast to move.
        String affected = String.valueOf(health.getDetails().get("affected"));
        assertTrue(affected.contains("CAD"), affected);
        assertTrue(affected.contains("PDFs and markup are unaffected"), affected);
    }
}
