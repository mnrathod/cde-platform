package com.cde.platform.conversion.api;

import com.cde.platform.model.User;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.RolePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the conversion endpoints accept, refuse and reveal.
 *
 * <p>The queue is real and the workers are running, so a submitted job is
 * genuinely picked up. The links point at hosts that do not resolve, which is
 * the point: what is under test here is the API's own behaviour — status
 * codes, the {@code Location} header, permissions, idempotency — and the job's
 * eventual failure is the pipeline's business, tested separately.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "cde.fetch.enabled=true",
    // No host allow-list, so the address rules are the only gate and a private
    // address is refused on its own merits rather than incidentally.
    "cde.fetch.permitted-hosts="
})
class ConversionJobControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;

    private static final String USERNAME = "conversion-api-user";

    @BeforeEach
    void ensureUserExists() {
        users.findByUsername(USERNAME).orElseGet(() -> users.save(User.builder()
            .username(USERNAME)
            .email("conversion-api@example.test")
            .password("{noop}irrelevant")
            .role(User.Role.ENGINEER)
            .build()));
    }

    /** A principal holding exactly what the role really grants — nothing more. */
    private RequestPostProcessor actingAs(User.Role role) {
        return user(USERNAME).authorities(RolePermissions.grantedTo(role).stream()
            .map(SimpleGrantedAuthority::new)
            .toList());
    }

    private static String requestBody(String url) {
        return """
            {"sourceUrl":"%s","targetFormat":"PDF"}""".formatted(url);
    }

    /** A host that will not resolve, so nothing is actually fetched. */
    private static String unresolvableUrl() {
        return "https://storage-" + UUID.randomUUID() + ".invalid/drawing.dwg";
    }

    private String jobIdFrom(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"jobId\":\"") + 9;
        return body.substring(start, body.indexOf('"', start));
    }

    @Nested
    @DisplayName("submission")
    class Submission {

        @Test
        @DisplayName("answers 202 with a job to poll, not the converted file")
        void submissionIsAccepted() throws Exception {
            // §7.1: bulk work returns a job id in under a second. There is no
            // synchronous variant, deliberately.
            MvcResult result = mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(unresolvableUrl())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.targetFormat").value("PDF"))
                .andReturn();

            assertThat(result.getResponse().getHeader("Location"))
                .as("the Location header must name the job to poll")
                .isEqualTo("/api/conversions/" + jobIdFrom(result));
        }

        @Test
        @DisplayName("refuses the cloud metadata endpoint at submission, without a lookup")
        void refusesMetadataEndpoint() throws Exception {
            // The SSRF gate, reached through the API rather than only in the
            // policy's own tests — a check that exists but is not wired to the
            // endpoint protects nothing. An address literal needs no DNS, so
            // it is judged here rather than a minute later in the worker.
            mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody("https://169.254.169.254/latest/meta-data/")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").exists());
        }

        @Test
        @DisplayName("refuses a private address literal at submission")
        void refusesPrivateAddressLiteral() throws Exception {
            for (String address : new String[] { "127.0.0.1", "10.0.4.17", "192.168.1.5" }) {
                mockMvc.perform(post("/api/conversions")
                        .with(actingAs(User.Role.ENGINEER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("https://" + address + "/secrets")))
                    .andExpect(status().isUnprocessableEntity());
            }
        }

        @Test
        @DisplayName("accepts a host name here and leaves its address to the fetch")
        void hostNamesAreResolvedByTheFetchNotBySubmission() throws Exception {
            // Deliberate: resolving on this path would put a network round trip
            // inside a §7.1 budget, and would report a momentarily unresolvable
            // host as "not permitted". A name that resolves somewhere private
            // is refused by the fetcher before it connects — see
            // FetchDestinationPolicyTest, which covers rebinding directly.
            mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(unresolvableUrl())))
                .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("refuses a file: URL, which would be an arbitrary file read")
        void refusesFileScheme() throws Exception {
            // Caught by validation before the policy sees it, which is the
            // right order: the pattern gives a clearer message for an
            // obviously wrong value.
            mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody("file:///etc/passwd")))
                .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("returns the same job when a submission is retried with the same key")
        void repeatedSubmissionIsIdempotent() throws Exception {
            // §3.4. A client that times out against a 202 should retry; without
            // this it pays for the conversion twice and holds two job ids for
            // one intent.
            String key = "test-" + UUID.randomUUID();
            String url = unresolvableUrl();

            String first = jobIdFrom(mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(url)))
                .andExpect(status().isAccepted())
                .andReturn());

            String second = jobIdFrom(mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(url)))
                .andExpect(status().isAccepted())
                .andReturn());

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("treats submissions without a key as separate jobs")
        void submissionsWithoutAKeyAreDistinct() throws Exception {
            // The key is optional, and every keyless submission must not
            // collide with every other one on an empty string.
            String url = unresolvableUrl();

            String first = jobIdFrom(mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(url)))
                .andExpect(status().isAccepted()).andReturn());

            String second = jobIdFrom(mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(url)))
                .andExpect(status().isAccepted()).andReturn());

            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("refuses a body with no URL rather than fetching nothing")
        void refusesMissingUrl() throws Exception {
            mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"targetFormat":"PDF"}"""))
                .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    @DisplayName("permissions")
    class Permissions {

        @Test
        @DisplayName("a viewer cannot submit a conversion")
        void viewersCannotSubmit() throws Exception {
            // Built from what RolePermissions actually grants, so widening the
            // viewer role makes this fail rather than quietly become untrue.
            mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.VIEWER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(unresolvableUrl())))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a viewer cannot read a conversion either")
        void viewersCannotRead() throws Exception {
            mockMvc.perform(get("/api/conversions/{id}", UUID.randomUUID())
                    .with(actingAs(User.Role.VIEWER)))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unauthenticated caller gets nothing")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get("/api/conversions/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("reading and cancelling")
    class ReadingAndCancelling {

        @Test
        @DisplayName("a job that does not exist answers 404, like another tenant's would")
        void unknownJobIsNotFound() throws Exception {
            mockMvc.perform(get("/api/conversions/{id}", UUID.randomUUID())
                    .with(actingAs(User.Role.ENGINEER)))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("cancelling records the request and reports it")
        void cancellationIsRecorded() throws Exception {
            String jobId = jobIdFrom(mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(unresolvableUrl())))
                .andExpect(status().isAccepted()).andReturn());

            mockMvc.perform(delete("/api/conversions/{id}", jobId)
                    .with(actingAs(User.Role.ENGINEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId));
        }

        @Test
        @DisplayName("cancelling a job that does not exist answers 404, not 500")
        void cancellingUnknownJobIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/conversions/{id}", UUID.randomUUID())
                    .with(actingAs(User.Role.ENGINEER)))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("listing answers with this organisation's jobs")
        void listingWorks() throws Exception {
            mockMvc.perform(get("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        }
    }

    @Nested
    @DisplayName("what the response reveals")
    class ResponseContent {

        @Test
        @DisplayName("never echoes the source link back, only its host")
        void responseCarriesTheHostNotTheLink() throws Exception {
            // The link is a bearer credential. Echoing it would put it in the
            // integrator's own logs, which is the leak this design avoids.
            String url = "https://storage-" + UUID.randomUUID()
                       + ".invalid/plan.dwg?sig=synthetic-not-a-real-signature";

            MvcResult result = mockMvc.perform(post("/api/conversions")
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(url)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceHost").exists())
                .andReturn();

            assertThat(result.getResponse().getContentAsString())
                .doesNotContain("sig=")
                .doesNotContain("synthetic-not-a-real-signature")
                .doesNotContain("/plan.dwg");
        }
    }
}
