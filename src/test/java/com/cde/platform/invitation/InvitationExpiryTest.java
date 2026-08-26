package com.cde.platform.invitation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An invitation stops working when it expires.
 *
 * <p>Its own class because it needs its own configuration: the validity is days
 * long by default, and the alternative to shortening it is either a test that
 * sleeps for a week or one that reaches into the database to wind the row back.
 * The second is what this originally did, and it could not work — the row
 * belongs to a tenant the test is not inside, so the test would have had to
 * defeat the very control it exists to rely on.
 *
 * <p>Waiting out a one-second validity exercises the real path instead,
 * including the {@code expires_at > now()} in the SQL that resolves the tenant.
 */
@SpringBootTest(properties = "cde.tenancy.invitation-validity=PT1S")
@AutoConfigureMockMvc
class InvitationExpiryTest {

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    @Test
    @DisplayName("an expired invitation is refused")
    void expiryStopsRedemption() throws Exception {
        String username = unique("host");
        String founded = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}
                    """.formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String host = objectMapper.readTree(founded).get("token").asText();

        String email = unique("stale") + "@example.test";
        String issued = mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","role":"ENGINEER"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(issued).get("token").asText();

        Thread.sleep(1_500);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s","password":"%s","invitationToken":"%s"}
                    """.formatted(unique("too-slow"), email, PASSWORD, token)))
            .andExpect(status().isUnprocessableEntity());
    }
}
