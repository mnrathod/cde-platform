package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call this API from somebody else's page.
 *
 * <p>The configuration was {@code allowedOrigins("*")} with every header
 * reflected. That is not a permissive setting so much as the setting that
 * turns the same-origin policy off: any page a user visited could call this
 * API from their browser and read the reply. Nothing failed as a result, which
 * is why it survived.
 */
class CrossOriginPolicyTest {

    private static final String SOMEBODY_ELSES_SITE = "https://not-ours.example";

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @DisplayName("with no origins configured — the default")
    class ClosedByDefault {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("no cross-origin caller is granted access")
        void refusesEveryOrigin() throws Exception {
            mockMvc.perform(options("/api/projects")
                    .header("Origin", SOMEBODY_ELSES_SITE)
                    .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Nested
    @SpringBootTest(properties = "cde.web.allowed-origins=https://app.example.test")
    @AutoConfigureMockMvc
    @DisplayName("with one origin named")
    class NamedOriginOnly {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("the named origin is allowed")
        void allowsTheNamedOrigin() throws Exception {
            mockMvc.perform(options("/api/projects")
                    .header("Origin", "https://app.example.test")
                    .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",
                                           "https://app.example.test"));
        }

        @Test
        @DisplayName("every other origin is still refused")
        void refusesAnyOtherOrigin() throws Exception {
            mockMvc.perform(options("/api/projects")
                    .header("Origin", SOMEBODY_ELSES_SITE)
                    .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        @DisplayName("the permitted request headers are enumerated, not reflected")
        void doesNotReflectArbitraryRequestHeaders() throws Exception {
            // Reflecting whatever the caller asks for makes the allow-list a
            // formality: the browser is told yes to anything it proposes.
            mockMvc.perform(options("/api/projects")
                    .header("Origin", "https://app.example.test")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "X-Something-Invented"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Nested
    @DisplayName("with a wildcard configured")
    class WildcardRefused {

        @Test
        @DisplayName("startup fails rather than accepting it")
        void rejectsWildcardOrigins() {
            var properties = new WebSecurityHeadersProperties();
            properties.setAllowedOrigins(java.util.List.of("https://*.example.test"));

            // Reported at startup, not at the first cross-origin request: a
            // wildcard that is never exercised in testing is a wildcard that
            // reaches production.
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                properties::rejectWildcardOrigins);
        }
    }
}
