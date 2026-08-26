package com.cde.platform.invitation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may decide the membership of an organisation.
 *
 * <p>{@code tenant.user:manage} is the authority to decide who is inside the
 * isolation boundary; every other permission only decides what somebody already
 * inside may do. So it deliberately sits outside the ISO 19650 container
 * vocabulary, where an engineer and a reviewer hold different powers and
 * neither is a superset of the other — an engineer holding
 * {@code container:write} must not thereby be able to add accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvitationPermissionTest {

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private String tokenOf(String response) throws Exception {
        return objectMapper.readTree(response).get("token").asText();
    }

    private String register(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}
                    """.formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    /** Founds an organisation and returns a member holding the given role. */
    private String memberWithRole(String role) throws Exception {
        String host = tokenOf(register(unique("admin")));
        String email = unique("member") + "@example.test";

        String issued = mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","role":"%s"}
                    """.formatted(email, role)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        return tokenOf(mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s","password":"%s","invitationToken":"%s"}
                    """.formatted(unique("member"), email, PASSWORD,
                                  objectMapper.readTree(issued).get("token").asText())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("an engineer cannot invite anyone")
    void engineersCannotInvite() throws Exception {
        String engineer = memberWithRole("ENGINEER");

        mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + engineer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"someone@example.test","role":"ADMIN"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a reviewer cannot invite anyone either")
    void reviewersCannotInvite() throws Exception {
        // The mirror of the above. Without it, a fix that granted the
        // permission to every authenticated caller would still pass the
        // engineer case if engineers happened to be checked separately.
        String reviewer = memberWithRole("REVIEWER");

        mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + reviewer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"someone@example.test","role":"VIEWER"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an engineer cannot list or revoke invitations")
    void engineersCannotSeeOrWithdrawInvitations() throws Exception {
        String engineer = memberWithRole("ENGINEER");

        mockMvc.perform(get("/api/invitations")
                .header("Authorization", "Bearer " + engineer))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/invitations/1")
                .header("Authorization", "Bearer " + engineer))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an administrator cannot revoke another organisation's invitation")
    void invitationsAreInvisibleAcrossOrganisations() throws Exception {
        String firstAdmin = tokenOf(register(unique("admin-a")));
        String secondAdmin = tokenOf(register(unique("admin-b")));

        String issued = mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + firstAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"theirs@example.test","role":"ENGINEER"}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long invitationId = objectMapper.readTree(issued).get("id").asLong();

        // 404 rather than 403, and that is the intended answer: the row is
        // invisible under the tenant policy, so this genuinely cannot tell
        // "not yours" from "not there" — and a 403 would confirm it exists.
        mockMvc.perform(delete("/api/invitations/" + invitationId)
                .header("Authorization", "Bearer " + secondAdmin))
            .andExpect(status().isNotFound());

        // Still redeemable, so the failed revocation really was a no-op.
        mockMvc.perform(get("/api/invitations")
                .header("Authorization", "Bearer " + firstAdmin))
            .andExpect(status().isOk());
    }
}
