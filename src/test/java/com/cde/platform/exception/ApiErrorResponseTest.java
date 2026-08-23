package com.cde.platform.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A wrong method, an unparseable body and an unknown path used to reach the
 * client as an empty 403, because Spring resolves them itself and forwards to
 * /error — a dispatch that carried no authentication and was rejected.
 *
 * That made a client-side mistake look like a permissions problem, with no
 * message to act on. These assert the status a caller can actually act on, and
 * that the reply carries a problem document — text to act on, and a trace id
 * to quote — rather than an empty body.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiErrorResponseTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("a wrong HTTP method reports 405, not 403")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        // /verify is a POST.
        mockMvc.perform(get("/api/signatures/any-signature-id/verify"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.detail").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @WithMockUser
    @DisplayName("an unparseable body reports 400, not 403")
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/annotations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @WithMockUser
    @DisplayName("a value outside an enum reports 400, not 403")
    void unknownEnumValueIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/annotations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"documentId":1,"pageNumber":1,"type":"NOT_A_REAL_TYPE",
                     "shapeData":"{}","comment":"x"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @WithMockUser
    @DisplayName("an error reply never carries a stack trace or Java type names")
    void errorsDoNotLeakInternals() throws Exception {
        // The parser's own message names the enum class and its constants;
        // returning it verbatim would leak internals into the API.
        String body = mockMvc.perform(post("/api/annotations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"documentId":1,"pageNumber":1,"type":"NOT_A_REAL_TYPE",
                     "shapeData":"{}","comment":"x"}"""))
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
            .doesNotContain("com.cde.platform")
            .doesNotContain("Exception")
            .doesNotContain("at org.springframework");
    }

    @Test
    @DisplayName("an unauthenticated request is still 401 or 403, not 404")
    void securityStillApplies() throws Exception {
        // Permitting /error must not make protected endpoints reachable.
        mockMvc.perform(get("/api/documents/1"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                if (status != 401 && status != 403) {
                    throw new AssertionError("expected 401/403 but got " + status);
                }
            });
    }
}
