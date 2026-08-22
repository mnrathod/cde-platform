package com.cde.platform.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator's useful endpoints are also its dangerous ones.
 *
 * <p>Two things have to hold at once: Kubernetes must be able to read the
 * probes with no credentials at all, and nobody without them may read metrics,
 * the environment or the config. Getting that wrong does not break anything
 * visibly — the application works perfectly either way — so it is asserted
 * here rather than left to be noticed.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Spring Boot switches metrics export off inside tests, so without this the
// Prometheus endpoint is absent here and present in production — and a test
// that says "not exposed" would be describing the test harness rather than
// the application.
@AutoConfigureObservability
class ActuatorEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Kubernetes can read both probes without credentials")
    void probesAreReachableAnonymously() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Anonymous health reports a status but no internal detail")
    void healthHidesComponentsFromAnonymousCallers() throws Exception {
        // The status itself is not sensitive. What lives under it is: which
        // database, which converter URL, how much disk is left.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("An admin sees the components, including the converter")
    void healthShowsComponentsToAdmins() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.converter").exists());
    }

    @Test
    @DisplayName("Metrics are closed to anonymous callers")
    void metricsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Metrics are open to an admin, and Prometheus can scrape them")
    void metricsAreAvailableToAdmins() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("The endpoints that dump configuration are not exposed at all")
    void dangerousEndpointsAreNotExposed() throws Exception {
        // Asserted as an admin on purpose. Anonymously these would be refused
        // whether or not they were exposed, which proves nothing; a 404 for
        // someone who would otherwise be allowed in proves they are absent.
        // /env and /configprops would both print cde.jwt.secret.
        for (String endpoint : new String[] {
                "/actuator/env", "/actuator/configprops", "/actuator/beans",
                "/actuator/heapdump", "/actuator/threaddump", "/actuator/loggers" }) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    @DisplayName("An ordinary signed-in user is not an operator")
    void metricsAreClosedToNonAdmins() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
    }
}
