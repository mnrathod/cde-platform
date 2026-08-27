package com.cde.platform.audit;

import com.cde.platform.repository.TenantRepository;
import com.cde.platform.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail, driven rather than inspected.
 *
 * <p>Two properties matter and neither can be established by reading the code:
 * that events actually reach the trail on the paths that produce them, and that
 * the application genuinely cannot modify what is there. The second is a
 * database grant, so the only way to know it holds is to try the write and be
 * refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    @Autowired MockMvc mockMvc;
    @Autowired AuditEventRepository events;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transactions;
    @Autowired TenantRepository tenants;

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private String registerAndGetToken(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}
                    """.formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"token\":\"") + 9;
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    @DisplayName("registering and signing in both reach the organisation's trail")
    void recordsAuthenticationEvents() throws Exception {
        String username = unique("auditor");
        String token = registerAndGetToken(username);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","password":"%s"}
                    """.formatted(username, PASSWORD)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/audit-events").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            // Newest first, so the sign-in leads and the registration that
            // created the organisation is beneath it.
            .andExpect(jsonPath("$.content[0].action").value("SIGN_IN"))
            .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS"))
            .andExpect(jsonPath("$.content[0].actorLabel").value(username))
            .andExpect(jsonPath("$.content[1].action").value("REGISTRATION"))
            // Contiguous from 1: this organisation was created by that
            // registration, so its chain starts there.
            .andExpect(jsonPath("$.content[1].sequenceNumber").value(1));
    }

    @Test
    @DisplayName("a refused sign-in is recorded against the organisation it was aimed at")
    void recordsFailedSignIn() throws Exception {
        String username = unique("target");
        String token = registerAndGetToken(username);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","password":"the-wrong-password-entirely"}
                    """.formatted(username)))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/audit-events")
                .header("Authorization", "Bearer " + token)
                .param("action", "SIGN_IN"))
            .andExpect(status().isOk())
            // Visible to the administrator of the organisation whose account
            // was targeted, which is who needs to see it. The caller learns
            // nothing either way: the reply to the attempt is identical.
            .andExpect(jsonPath("$.content[0].outcome").value("FAILURE"))
            .andExpect(jsonPath("$.content[0].actorUserId").doesNotExist());
    }

    @Test
    @DisplayName("a refusal on authority is recorded with what was attempted")
    void recordsAuthorisationDenial() throws Exception {
        // A founder administers their own organisation and so holds every
        // permission in it — there is nothing inside it they can be refused.
        // A genuine denial needs an account with a lesser role, which means
        // inviting one. That is also the realistic shape of the event: the
        // account being refused is a member, not a stranger.
        String founderName = unique("founder");
        String founderToken = registerAndGetToken(founderName);

        String invitedEmail = unique("viewer") + "@example.test";
        String invitation = mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + founderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","role":"VIEWER"}
                    """.formatted(invitedEmail)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int tokenStart = invitation.indexOf("\"token\":\"") + 9;
        String invitationToken =
            invitation.substring(tokenStart, invitation.indexOf('"', tokenStart));

        String viewerBody = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s","password":"%s","invitationToken":"%s"}
                    """.formatted(unique("viewer-account"), invitedEmail,
                                  PASSWORD, invitationToken)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int start = viewerBody.indexOf("\"token\":\"") + 9;
        String viewerToken = viewerBody.substring(start, viewerBody.indexOf('"', start));

        // A viewer holds container:read and nothing else, so inviting anyone
        // is refused — the permission to decide who is inside the boundary.
        mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"someone@example.test","role":"ADMIN"}
                    """))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/audit-events")
                .header("Authorization", "Bearer " + founderToken)
                .param("action", "AUTHORISATION_DENIED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].outcome").value("DENIED"))
            // The path is what makes the record actionable: "denied" alone
            // says only that something was refused.
            .andExpect(jsonPath("$.content[0].changeSummary").value(
                org.hamcrest.Matchers.containsString("/api/invitations")));
    }

    @Test
    @DisplayName("one organisation cannot read another's trail")
    void isTenantIsolated() throws Exception {
        String firstToken = registerAndGetToken(unique("first"));
        String secondToken = registerAndGetToken(unique("second"));

        String firstTrail = mockMvc.perform(get("/api/audit-events")
                .header("Authorization", "Bearer " + firstToken))
            .andReturn().getResponse().getContentAsString();
        String secondTrail = mockMvc.perform(get("/api/audit-events")
                .header("Authorization", "Bearer " + secondToken))
            .andReturn().getResponse().getContentAsString();

        // Each sees only its own founding. An organisation's trail names who
        // did what inside it, so leaking one across the boundary leaks the
        // membership of the other.
        assertThat(firstTrail).doesNotContain("second-");
        assertThat(secondTrail).doesNotContain("first-");
    }

    @Test
    @DisplayName("the application cannot modify a record, whatever the code says")
    void isAppendOnlyAtTheDatabase() throws Exception {
        String username = unique("immutable");
        registerAndGetToken(username);

        // Bound explicitly. Without a tenant context the policy filters every
        // row, so findAll() came back empty and the test failed looking for a
        // record that was there — describing the missing context rather than
        // the grant it exists to check.
        Long tenantId = tenants.findAll().stream()
            .filter(tenant -> tenant.getSlug().startsWith("org-"))
            .map(tenant -> tenant.getId())
            .reduce((first, second) -> second)
            .orElseThrow();
        Long recordId = TenantContext.callAsTenant(tenantId, () ->
            transactions.execute(status ->
                events.findAll().stream().map(AuditEvent::getId).findFirst().orElseThrow()));

        // Native SQL, deliberately: going through the entity would only prove
        // that the entity has no setters, which is a convention. The property
        // worth having is that the database refuses — so a compromised
        // application holding full credentials still cannot rewrite what it
        // did. That is a GRANT, and this is what asks it.
        assertThatThrownBy(() -> TenantContext.runAsTenant(tenantId, () ->
            transactions.execute(status -> {
                entityManager.createNativeQuery(
                        "UPDATE audit_events SET actor_label = 'someone else' WHERE id = :id")
                    .setParameter("id", recordId)
                    .executeUpdate();
                entityManager.flush();
                return null;
            })))
            .isInstanceOfAny(PersistenceException.class,
                             org.springframework.dao.DataAccessException.class);

        assertThatThrownBy(() -> TenantContext.runAsTenant(tenantId, () ->
            transactions.execute(status -> {
                entityManager.createNativeQuery("DELETE FROM audit_events WHERE id = :id")
                    .setParameter("id", recordId)
                    .executeUpdate();
                entityManager.flush();
                return null;
            })))
            .isInstanceOfAny(PersistenceException.class,
                             org.springframework.dao.DataAccessException.class);

        // Still there afterwards, which is the property the two refusals are
        // for: asserting that the statements threw would not distinguish
        // "refused" from "threw after succeeding".
        assertThat(TenantContext.callAsTenant(tenantId, () -> events.findById(recordId)))
            .isPresent();
    }

    @Test
    @DisplayName("an intact chain verifies")
    void verifiesAnIntactChain() throws Exception {
        String token = registerAndGetToken(unique("chained"));

        // Several records, so the chain has links rather than one node.
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(get("/actuator/loggers").header("Authorization", "Bearer " + token));
        }

        String verification = mockMvc.perform(get("/api/audit-events/verification")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Asserted against the whole body so a failure says which record broke
        // and why, rather than only that a boolean was false.
        assertThat(verification).contains("\"intact\":true");
    }
}
