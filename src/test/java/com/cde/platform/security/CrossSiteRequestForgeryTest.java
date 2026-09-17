package com.cde.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The forgery protection that arrived with the session cookie.
 *
 * <p>CSRF was disabled, and while a bearer header was the only credential that
 * was a defensible position rather than an oversight: a browser does not
 * attach an Authorization header of its own accord, so a cross-site form had
 * nothing to forge with. A cookie is different — it rides along on a
 * cross-site request unless something stops it, which is the whole reason
 * §5.4 asks for both {@code SameSite} and a token.
 *
 * <p>The protection is scoped rather than blanket, and the scoping is the part
 * worth testing: switching it on for everything would have broken the mobile
 * SDKs, which authenticate by bearer token and are not exposed to this attack.
 * So these assert both directions — that a cookie-borne write without a token
 * is refused, and that a bearer-borne one is not asked for a token it has no
 * way to obtain.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrossSiteRequestForgeryTest {

    @Autowired MockMvc mockMvc;

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    private record Session(Cookie cookie, String token) {}

    private Session freshSession() throws Exception {
        String username = "csrf-" + System.nanoTime();
        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}"""
                    .formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn().getResponse();

        Cookie cookie = response.getCookie(SessionCookie.NAME);
        assertThat(cookie).as("session cookie").isNotNull();

        String body = response.getContentAsString();
        int start = body.indexOf("\"token\":\"") + 9;
        return new Session(cookie, body.substring(start, body.indexOf('"', start)));
    }

    @Test
    @DisplayName("a cookie-borne write without a token is refused")
    void refusesACookieWriteWithoutAToken() throws Exception {
        // The attack this exists for: a form on another site, posting here,
        // with the browser helpfully attaching the session cookie.
        Session session = freshSession();

        mockMvc.perform(post("/api/auth/logout").cookie(session.cookie()))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the same write succeeds with a token")
    void acceptsACookieWriteWithAToken() throws Exception {
        // The other half. A refusal that also refused legitimate requests
        // would be a broken application rather than a protected one.
        Session session = freshSession();

        mockMvc.perform(post("/api/auth/logout").cookie(session.cookie()).with(csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a bearer-borne write is not asked for a token")
    void doesNotDemandATokenFromABearerCaller() throws Exception {
        // A native client has no cookie to be abused and no origin to read a
        // token from. Demanding one would break the published SDK contract to
        // defend against an attack it cannot suffer.
        Session session = freshSession();

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + session.token()))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("signing in needs no token")
    void doesNotDemandATokenToSignIn() throws Exception {
        // A login that required a token fetched beforehand would fail for
        // anyone arriving with a cold cache, and it carries no session to
        // abuse in the first place.
        String username = "csrf-login-" + System.nanoTime();
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}"""
                    .formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","password":"%s"}""".formatted(username, PASSWORD)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("reading needs no token")
    void doesNotDemandATokenToRead() throws Exception {
        // Safe methods are not supposed to change anything, and requiring a
        // token on them would break ordinary navigation to no purpose.
        Session session = freshSession();

        mockMvc.perform(get("/api/auth/session").cookie(session.cookie()))
            .andExpect(status().isOk());
    }
}
