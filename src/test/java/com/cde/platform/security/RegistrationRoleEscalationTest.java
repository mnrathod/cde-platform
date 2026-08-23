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
    @DisplayName("an anonymous caller asking to be an administrator is made an engineer")
    void aRequestedRoleIsNotHonoured() throws Exception {
        String username = "escalation-attempt-" + System.nanoTime();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s",
                     "email":"%s@example.test",
                     "password":"correct-horse-battery-staple-42",
                     "role":"ADMIN"}""".formatted(username, username)))
            .andExpect(status().isCreated())
            // The reply is explicit about what was granted rather than silent
            // about what was refused.
            .andExpect(jsonPath("$.role").value("ENGINEER"));

        // And the stored row agrees — the response could be right while the
        // row that authorisation actually reads from is wrong.
        assertThat(userRepository.findByUsername(username))
            .get()
            .extracting(User::getRole)
            .isEqualTo(User.Role.ENGINEER);
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
