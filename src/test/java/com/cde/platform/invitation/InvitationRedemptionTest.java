package com.cde.platform.invitation;

import com.cde.platform.repository.InvitationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Joining an existing organisation.
 *
 * <p>An invitation is the only thing that lets registration name a tenant
 * without a stranger asserting one, so what it refuses matters as much as what
 * it admits. Every refusal below returns the same answer on purpose:
 * distinguishing "expired" from "no such invitation" tells someone holding a
 * guessed token whether they guessed a real one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvitationRedemptionTest {

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    @Autowired MockMvc mockMvc;
    @Autowired InvitationRepository invitations;
    @Autowired ObjectMapper objectMapper;

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** An organisation with one administrator, and that administrator's token. */
    private String foundOrganisation() throws Exception {
        String username = unique("host");
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}
                    """.formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private JsonNode invite(String hostToken, String email, String role) throws Exception {
        String response = mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + hostToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","role":"%s"}
                    """.formatted(email, role)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private org.springframework.test.web.servlet.ResultActions redeem(
            String username, String email, String token) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","email":"%s","password":"%s","invitationToken":"%s"}
                """.formatted(username, email, PASSWORD, token)));
    }

    // ── Admission ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an invitation joins the inviting organisation, with the invited role")
    void redeemingJoinsTheInvitingTenant() throws Exception {
        String host = foundOrganisation();

        // Something to be visible only from inside.
        mockMvc.perform(post("/api/projects").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Shared work","phase":"DESIGN"}
                    """))
            .andExpect(status().isCreated());

        String invitedEmail = unique("guest") + "@example.test";
        String token = invite(host, invitedEmail, "REVIEWER").get("token").asText();

        String joined = redeem(unique("guest-user"), invitedEmail, token)
            .andExpect(status().isCreated())
            // The role is the inviter's choice, not the joiner's, and not the
            // ADMIN a founding registration would have received.
            .andExpect(jsonPath("$.role").value("REVIEWER"))
            .andReturn().getResponse().getContentAsString();

        String guestToken = objectMapper.readTree(joined).get("token").asText();

        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + guestToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Shared work"));
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an invitation admits only the address it was issued to")
    void aForwardedInvitationDoesNotAdmitTheReader() throws Exception {
        String host = foundOrganisation();
        String token = invite(host, unique("intended") + "@example.test", "ENGINEER")
            .get("token").asText();

        // The token alone is not the credential. Without this check, an
        // invitation forwarded to a colleague — or lifted from an inbox —
        // admits whoever opens it.
        redeem(unique("interloper"), unique("someone-else") + "@example.test", token)
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("an invitation is spent once redeemed, and cannot be redeemed again")
    void aRedeemedInvitationCannotBeUsedAgain() throws Exception {
        String host = foundOrganisation();
        String email = unique("once") + "@example.test";
        String token = invite(host, email, "ENGINEER").get("token").asText();

        redeem(unique("first-arrival"), email, token).andExpect(status().isCreated());

        // 409, not the 422 an unusable invitation gets — and that is correct
        // rather than a leak in the wrong direction. Redeeming again means
        // presenting the invited address again, and that address now has an
        // account, so identity uniqueness answers first and the invitation
        // check is never reached.
        redeem(unique("second-arrival"), email, token).andExpect(status().isConflict());

        // Which means the single-use rule is not observable from the endpoint
        // while the first account exists. It still matters — delete that
        // account and the address frees up, and without this the token would
        // admit somebody all over again — so it is asserted where it is
        // actually recorded.
        String listing = mockMvc.perform(get("/api/invitations")
                .header("Authorization", "Bearer " + host))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode spent = objectMapper.readTree(listing).get(0);
        assertThat(spent.get("email").asText()).isEqualTo(email);
        assertThat(spent.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(spent.get("acceptedAt").isNull())
            .as("a spent invitation records when it was spent")
            .isFalse();
    }

    @Test
    @DisplayName("a revoked invitation is refused")
    void revokingStopsRedemption() throws Exception {
        String host = foundOrganisation();
        String email = unique("revoked") + "@example.test";
        JsonNode issued = invite(host, email, "ENGINEER");

        mockMvc.perform(delete("/api/invitations/" + issued.get("id").asLong())
                .header("Authorization", "Bearer " + host))
            .andExpect(status().isNoContent());

        redeem(unique("too-late"), email, issued.get("token").asText())
            .andExpect(status().isUnprocessableEntity());
    }

    // Expiry is exercised in InvitationExpiryTest, which configures a validity
    // short enough to wait out. Winding the row back from here would need to
    // read an invitation belonging to a tenant this test is not inside, which
    // the policy correctly refuses — the test would have had to defeat the
    // control it is meant to rely on.

    @Test
    @DisplayName("an unknown token is refused, and says no more than a real one does")
    void anInventedTokenIsRefused() throws Exception {
        redeem(unique("guesser"), unique("guesser") + "@example.test",
               "cdeinv_this-token-was-never-issued-by-anyone")
            .andExpect(status().isUnprocessableEntity());
    }

    // ── The token is shown once ──────────────────────────────────────────────

    @Test
    @DisplayName("a listing never carries the token")
    void listingDoesNotDiscloseTokens() throws Exception {
        String host = foundOrganisation();
        String email = unique("listed") + "@example.test";
        String token = invite(host, email, "ENGINEER").get("token").asText();

        String listing = mockMvc.perform(get("/api/invitations")
                .header("Authorization", "Bearer " + host))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // A readable invitation table would be a set of credentials for every
        // pending account, so the listing has to be useless for redemption.
        assertThat(listing)
            .as("the listing must not carry the token, nor anything it hashes from")
            .doesNotContain(token)
            .contains(email);
    }

}
