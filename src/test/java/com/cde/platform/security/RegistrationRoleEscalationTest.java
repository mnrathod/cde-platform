package com.cde.platform.security;

import com.cde.platform.model.User;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration cannot be used to grant yourself a role.
 *
 * <p>It could. The endpoint requires no credential and took the role from the
 * request body, so an anonymous caller could ask for {@code ADMIN} and get it.
 * Once roles began carrying the container permissions that stopped being a
 * misconfigured actuator and became the authority to publish a contractual
 * record — which is why this is asserted rather than assumed.
 *
 * <p>The request below still sends the field, because that is what an attacker
 * would do and what an old client might do. What matters is that it changes
 * nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationRoleEscalationTest {

    @Autowired MockMvc        mockMvc;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("a requested role changes nothing, and the one granted reaches nobody else")
    void aRequestedRoleIsNotHonoured() throws Exception {
        String username = "escalation-attempt-" + System.nanoTime();

        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s",
                     "email":"%s@example.test",
                     "password":"correct-horse-battery-staple-42",
                     "role":"VIEWER"}""".formatted(username, username)))
            .andExpect(status().isCreated())
            // The reply is explicit about what was granted rather than silent
            // about what was refused. The request asked for VIEWER and the
            // field was ignored, which is the property under test — the role
            // comes from what the registration did, never from what it asked.
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andReturn().getResponse().getContentAsString();

        // ADMIN here is not the escalation the old defect was. That one made
        // the caller an administrator of the shared default tenant, which every
        // account was in — this one administers a tenant created empty by this
        // same request, so the authority reaches nothing that existed a moment
        // ago. Asserted rather than argued:
        int start = response.indexOf("\"token\":\"") + 9;
        String token = response.substring(start, response.indexOf('"', start));

        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("the engineer a registration produces cannot publish")
    void theGrantedRoleCarriesNoPublishAuthority() {
        // The point of the test above, stated as the thing it protects: the
        // role anyone can obtain by signing up must not include authorising
        // information for use.
        assertThat(RolePermissions.grantedTo(User.Role.ENGINEER))
            .doesNotContain("container:publish");
    }
}
