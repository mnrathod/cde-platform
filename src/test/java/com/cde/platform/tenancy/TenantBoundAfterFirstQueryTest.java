package com.cde.platform.tenancy;

import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * A tenant bound part-way through a request still reaches the database.
 *
 * <p>Registration and sign-in both have to look a tenant up before they can
 * bind it — which tenant it is, is the answer to a query. With
 * {@code open-in-view} on, that first query pins a connection to the request,
 * and the connection was scoped when it was acquired: unbound. Every later
 * statement then ran with no tenant, so the insert was refused by the policy's
 * {@code WITH CHECK} and registration failed outright.
 *
 * <p>The rest of the suite could not see this. {@code DefaultTenantExtension}
 * binds a tenant before each test, so every connection is acquired already
 * scoped and the mismatch never arises — the fixture was quietly holding the
 * application to an ordering that production does not follow. This test clears
 * the binding first, which is what an unauthenticated request actually looks
 * like.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantBoundAfterFirstQueryTest {

    @Autowired MockMvc        mockMvc;
    @Autowired UserRepository userRepository;

    private java.util.Optional<Long> bound;

    @BeforeEach
    void unbindTheTenantTheFixtureBound() {
        bound = TenantContext.currentTenantId();
        TenantContextBinder.clear();
    }

    @AfterEach
    void restoreIt() {
        TenantContextBinder.restore(bound);
    }

    @Test
    @DisplayName("registration works when nothing has established a tenant beforehand")
    void registrationSucceedsWithNoTenantBoundAtRequestStart() throws Exception {
        String username = "unbound-" + System.nanoTime();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test",
                     "password":"correct-horse-battery-staple-42"}"""
                    .formatted(username, username)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").exists());

        // Proven by signing in rather than by reading the row back, because the
        // account is now in a tenant of its own and the fixture's tenant cannot
        // see it — which is the isolation working, not an obstacle to route
        // around. Login resolves its own tenant from the username, so it
        // succeeds only if the row genuinely landed somewhere real; the
        // endpoint answering 201 is not enough on its own.
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","password":"correct-horse-battery-staple-42"}"""
                    .formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").exists());
    }

    @Test
    @DisplayName("signing in works when nothing has established a tenant beforehand")
    void loginSucceedsWithNoTenantBoundAtRequestStart() throws Exception {
        String username = "unbound-login-" + System.nanoTime();
        String password = "correct-horse-battery-staple-42";

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}"""
                    .formatted(username, username, password)))
            .andExpect(status().isCreated());

        // Sign-in resolves the tenant from the username, so it has the same
        // ordering — and it fails more quietly than registration did: reading
        // the users table with no tenant returns nothing, which is
        // indistinguishable from a wrong password.
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","password":"%s"}""".formatted(username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(username));
    }
}
