package com.cde.platform.invitation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an anonymous caller gets from the registration endpoint, per deployment.
 *
 * <p>The default lets anyone found their own organisation, which is what makes
 * the product usable without an operator provisioning anything. A deployment
 * serving named organisations, or a sovereign one where an account created by
 * whoever can reach the network is precisely the thing being defended against,
 * needs the endpoint to say no — so the mode is configuration rather than a
 * fork of this code.
 */
class SelfRegistrationModeTest {

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    private static String body(String username) {
        return """
            {"username":"%s","email":"%s@example.test","password":"%s"}
            """.formatted(username, username, PASSWORD);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    @Nested
    @SpringBootTest(properties = "cde.tenancy.self-registration=DISABLED")
    @AutoConfigureMockMvc
    @DisplayName("DISABLED")
    class Disabled {

        @Autowired MockMvc mockMvc;

        @Test
        @DisplayName("refuses everyone, with an answer that says what to do instead")
        void refusesEveryone() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON).content(body(unique("nobody"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/registration-closed"))
                // §1.4: what happened, why, and what to do next.
                .andExpect(jsonPath("$.detail").value(
                    org.hamcrest.Matchers.containsString("administrator")));
        }
    }

    @Nested
    @SpringBootTest(properties = "cde.tenancy.self-registration=INVITATION_ONLY")
    @AutoConfigureMockMvc
    @DisplayName("INVITATION_ONLY")
    class InvitationOnly {

        @Autowired MockMvc mockMvc;

        @Test
        @DisplayName("refuses a registration that presents no invitation")
        void refusesTheUninvited() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON).content(body(unique("uninvited"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/invitation-required"));
        }

        @Test
        @DisplayName("still refuses an invitation that is not real")
        void refusesAnInventedInvitation() throws Exception {
            // The mode gates the uninvited path; it must not become a way in
            // for anyone who sends the field at all.
            String username = unique("pretender");
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"username":"%s","email":"%s@example.test","password":"%s",
                         "invitationToken":"cdeinv_not-a-real-token"}
                        """.formatted(username, username, PASSWORD)))
                .andExpect(status().isUnprocessableEntity());
        }
    }
}
