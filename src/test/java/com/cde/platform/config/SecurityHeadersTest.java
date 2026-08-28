package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The response headers that switch on the browser's own protections.
 *
 * <p>None of these change what the API does, which is exactly why they were
 * absent and why their absence is invisible: every request succeeds either
 * way. What differs is whether a browser refuses to load an injected script,
 * downgrade to plain HTTP, leak the full URL in a referrer, or hand a page's
 * camera to something on it. These assertions are what notices a header being
 * dropped by a later edit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Every response carries the transport and content policies")
    void hardeningHeadersArePresent() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
            .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
            .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
            .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
            .andExpect(header().string("Permissions-Policy", containsString("geolocation=()")))
            .andExpect(header().string("Permissions-Policy", containsString("microphone=()")));
    }

    @Test
    @DisplayName("HSTS is sent over TLS and withheld over plain HTTP")
    void hstsFollowsTheTransport() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml").secure(true))
            .andExpect(header().string("Strict-Transport-Security",
                "max-age=31536000 ; includeSubDomains ; preload"));

        // Withheld deliberately, not missing. A browser ignores HSTS arriving
        // over plain HTTP — the header only means anything on a connection
        // that was already secure — so sending it there would assert a policy
        // nothing enforces and make a local deployment look protected.
        mockMvc.perform(get("/api/openapi.yaml"))
            .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @DisplayName("An authenticated response is never stored by a shared cache")
    void authenticatedResponsesAreNotCached() throws Exception {
        // A proxy or a browser holding one tenant's response and serving it to
        // the next request is a cross-tenant leak by another route.
        mockMvc.perform(get("/api/projects"))
            .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    @DisplayName("The API's own policy permits no script, style, frame or object at all")
    void apiPolicyIsDenyEverything() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml"))
            .andExpect(header().string("Content-Security-Policy",
                containsString("default-src 'none'")))
            .andExpect(header().string("Content-Security-Policy",
                containsString("frame-ancestors 'none'")))
            // The relaxation exists for the documentation page and must not
            // reach the API. Asserting the absence is the point: a later
            // widening of the docs policy that lands globally fails here.
            .andExpect(header().string("Content-Security-Policy",
                not(containsString("unsafe-inline"))))
            .andExpect(header().string("Content-Security-Policy",
                not(containsString("unsafe-eval"))));
    }

    @Test
    @DisplayName("The documentation page may apply its own inline styles, but no inline script")
    void documentationPolicyRelaxesStylesOnly() throws Exception {
        mockMvc.perform(get("/api/docs"))
            .andExpect(header().string("Content-Security-Policy",
                containsString("style-src 'self' 'unsafe-inline'")))
            // The property that matters. An inline style cannot execute; an
            // inline script can, and Swagger UI does not need one.
            .andExpect(header().string("Content-Security-Policy",
                containsString("script-src 'self'")))
            .andExpect(header().string("Content-Security-Policy",
                not(containsString("script-src 'self' 'unsafe-inline'"))));
    }

    @Test
    @DisplayName("The viewer cannot be framed by another origin")
    void framingIsRefused() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml"))
            .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
            .andExpect(header().string("Content-Security-Policy",
                containsString("frame-ancestors 'none'")));
    }
}
