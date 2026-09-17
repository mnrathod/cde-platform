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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser's session, and the ways it is meant to be unreachable.
 *
 * <p>The web client kept its token in {@code localStorage}, which §4.6 forbids
 * outright. The consequence is not theoretical: {@code localStorage} is
 * readable by any script on the origin, so one cross-site scripting bug
 * anywhere in the application yields a token that works for its whole lifetime
 * from anywhere. These assert the properties that replace it — that the cookie
 * is issued, that it authenticates, that script cannot read it, and that a
 * cross-site request cannot ride it.
 *
 * <p>Bearer tokens are asserted alongside, because the mobile SDKs authenticate
 * that way by published contract and the point of this change was to add a
 * safer option for browsers, not to take away the one native clients need.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionCookieAuthenticationTest {

    @Autowired MockMvc mockMvc;

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    /** Registers a fresh account and returns the sign-in response. */
    private MockHttpServletResponse registerFreshAccount() throws Exception {
        String username = "session-" + System.nanoTime();
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}"""
                    .formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn().getResponse();
    }

    private static String sessionCookieHeader(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream()
            .filter(header -> header.startsWith(SessionCookie.NAME + "="))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no session cookie in: " + response.getHeaders("Set-Cookie")));
    }

    private static Cookie sessionCookie(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(SessionCookie.NAME);
        assertThat(cookie).as("session cookie").isNotNull();
        return cookie;
    }

    @Test
    @DisplayName("signing up issues a session cookie script cannot read")
    void issuesAnHttpOnlyCookie() throws Exception {
        MockHttpServletResponse response = registerFreshAccount();

        Cookie cookie = sessionCookie(response);
        assertThat(cookie.isHttpOnly()).as("HttpOnly").isTrue();
        assertThat(cookie.getSecure()).as("Secure").isTrue();
        assertThat(cookie.getValue()).isNotBlank();
    }

    @Test
    @DisplayName("the cookie is same-site and host-scoped")
    void isLaxAndHostScoped() throws Exception {
        // The __Host- prefix is refused by browsers unless the cookie is
        // Secure, has Path=/ and names no Domain — which is what stops a
        // sibling subdomain, including one an attacker stood up, from setting
        // a cookie this application would read as its own session.
        String header = sessionCookieHeader(registerFreshAccount());

        assertThat(SessionCookie.NAME).startsWith("__Host-");
        assertThat(header).contains("Path=/");
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).doesNotContain("Domain=");
    }

    @Test
    @DisplayName("the cookie authenticates a request on its own")
    void authenticatesFromTheCookie() throws Exception {
        // The property the whole change rests on: no Authorization header
        // anywhere, and the request is still the signed-in user.
        MockHttpServletResponse signedUp = registerFreshAccount();

        mockMvc.perform(get("/api/auth/session").cookie(sessionCookie(signedUp)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the session reply names the account but carries no token")
    void namesTheAccountWithoutHandingBackTheToken() throws Exception {
        // Returning the token here would put it back within reach of script
        // and undo the reason for the cookie.
        MockHttpServletResponse signedUp = registerFreshAccount();

        String body = mockMvc.perform(get("/api/auth/session").cookie(sessionCookie(signedUp)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("username").contains("role");
        assertThat(body).doesNotContain("token");
        assertThat(body).doesNotContain(sessionCookie(signedUp).getValue());
    }

    @Test
    @DisplayName("a bearer token still authenticates, for clients with no cookie jar")
    void stillAcceptsABearerToken() throws Exception {
        // The mobile SDKs' published contract. Breaking it to fix a browser
        // problem would be fixing the wrong clients.
        MockHttpServletResponse signedUp = registerFreshAccount();
        String token = tokenFrom(signedUp.getContentAsString());

        mockMvc.perform(get("/api/auth/session").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("no credential at all is refused")
    void refusesAnAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/auth/session"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a rubbish cookie is refused rather than trusted")
    void refusesAForgedCookie() throws Exception {
        mockMvc.perform(get("/api/auth/session")
                .cookie(new Cookie(SessionCookie.NAME, "not.a.jwt")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("signing out clears the cookie")
    void clearsTheCookieOnLogout() throws Exception {
        // The cookie is HttpOnly, so script cannot delete what script cannot
        // see — signing out has to be something the server does.
        MockHttpServletResponse signedUp = registerFreshAccount();
        Cookie cookie = sessionCookie(signedUp);

        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/logout")
                .cookie(cookie)
                .header("Authorization", "Bearer " + tokenFrom(signedUp.getContentAsString())))
            .andExpect(status().isNoContent())
            .andReturn().getResponse();

        assertThat(sessionCookie(response).getMaxAge()).isZero();
    }

    private static String tokenFrom(String body) {
        int start = body.indexOf("\"token\":\"") + 9;
        return body.substring(start, body.indexOf('"', start));
    }
}
