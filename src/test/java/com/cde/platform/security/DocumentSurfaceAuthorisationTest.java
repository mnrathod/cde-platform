package com.cde.platform.security;

import com.cde.platform.model.User;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What each role may actually do to a document.
 *
 * <p>{@link EndpointPermissionCoverageTest} proves every endpoint names a
 * permission. That is not the same as the permission working: an annotation is
 * inert unless method security is switched on, the authority string matches
 * something a role is granted, and the filter chain lets the request reach the
 * method at all. Those are three separate ways for a guard to be decoration,
 * and only sending a request settles it.
 *
 * <p>The case that matters is the viewer. Its entire granted set is read
 * access, and before the document permissions existed it could delete a
 * document, burn a redaction into one, rearrange its pages, revoke a signature
 * and delete a project — because nothing on that surface checked anything
 * beyond "is this request authenticated". Every refusal asserted below is a
 * thing that used to succeed.
 *
 * <p>Permitted operations are asserted as <em>not 403</em> rather than as 200.
 * The identifiers are deliberately ones no document has, so a permitted caller
 * gets a 404 or a validation failure — which is the honest expectation, and it
 * keeps this file about authorisation instead of quietly becoming a second,
 * worse test of every endpoint's happy path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentSurfaceAuthorisationTest {

    private static final int FORBIDDEN = 403;

    /** No document, project or signature has this identifier. */
    private static final long ABSENT = 987_654_321L;

    // Bodies that satisfy Bean Validation. They have to: @Valid runs during
    // argument resolution, which is before the method-security interceptor, so
    // a malformed body is answered 422 and the guard under test is never
    // reached. Every one of these cases passed for that reason on first run.
    private static final String REDACT_BODY =
        "{\"regions\":[{\"page\":1,\"x\":0,\"y\":0,\"width\":10,\"height\":10}]}";
    private static final String FLATTEN_BODY =
        "{\"shapes\":[{\"tool\":\"rect\",\"pageNumber\":1}]}";
    private static final String ARRANGE_BODY = "{\"pages\":[{\"page\":1}]}";
    private static final String ANNOTATION_BODY =
        "{\"documentId\":" + ABSENT + ",\"type\":\"MARKUP\",\"pageNumber\":1,"
        + "\"shapeData\":\"{}\"}";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;

    private static final String USERNAME = "authorisation-matrix-user";

    @BeforeEach
    void ensureUserExists() {
        users.findByUsername(USERNAME).orElseGet(() -> users.save(User.builder()
            .username(USERNAME)
            .email("authorisation-matrix@example.test")
            .password("{noop}irrelevant")
            .role(User.Role.VIEWER)
            .build()));
    }

    /**
     * A principal carrying exactly what the role grants.
     *
     * <p>Built from {@link RolePermissions} rather than from a hand-written
     * list, so a permission added to a role reaches these cases and a
     * permission quietly removed from one breaks them.
     */
    private RequestPostProcessor actingAs(User.Role role) {
        return user(USERNAME).authorities(RolePermissions.grantedTo(role).stream()
            .map(SimpleGrantedAuthority::new)
            .toList());
    }

    private int statusFor(User.Role role, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.with(actingAs(role)).with(csrf()))
            .andReturn().getResponse().getStatus();
    }

    /**
     * The CSRF token every state-changing cookie-authenticated request needs.
     *
     * <p>Without it these requests are refused at the filter chain with a 403
     * that looks exactly like an authorisation failure — which would make every
     * "is refused" case below pass for the wrong reason, including for roles
     * that should be allowed through.
     */
    private static RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.csrf();
    }

    private void assertRefused(User.Role role, AbstractMockHttpServletRequestBuilder<?> request,
                               String what) throws Exception {
        assertThat(statusFor(role, request))
            .as("%s must be refused to a %s", what, role)
            .isEqualTo(FORBIDDEN);
    }

    private void assertNotRefused(User.Role role, AbstractMockHttpServletRequestBuilder<?> request,
                                  String what) throws Exception {
        assertThat(statusFor(role, request))
            .as("%s must not be refused to a %s on permission grounds", what, role)
            .isNotEqualTo(FORBIDDEN);
    }

    @Nested
    @DisplayName("a viewer, whose only granted authority is reading")
    class ViewerIsReadOnly {

        @Test
        @DisplayName("cannot delete a document")
        void cannotDeleteADocument() throws Exception {
            assertRefused(User.Role.VIEWER, delete("/api/documents/" + ABSENT),
                "deleting a document");
        }

        @Test
        @DisplayName("cannot upload one")
        void cannotUpload() throws Exception {
            assertRefused(User.Role.VIEWER,
                multipart("/api/documents/upload")
                    .file(new MockMultipartFile("file", "plan.pdf",
                          MediaType.APPLICATION_PDF_VALUE, "%PDF-1.7".getBytes()))
                    .param("projectId", String.valueOf(ABSENT)).param("name", "GA Plan"),
                "uploading a document");
        }

        @Test
        @DisplayName("cannot change a document's status")
        void cannotChangeStatus() throws Exception {
            assertRefused(User.Role.VIEWER,
                patch("/api/documents/" + ABSENT + "/status").param("status", "IN_REVIEW"),
                "changing a document's status");
        }

        @Test
        @DisplayName("cannot burn a redaction into one")
        void cannotRedact() throws Exception {
            // The operation this role could previously perform that is least
            // recoverable: redaction removes content on purpose, so there is
            // nothing to undo afterwards.
            assertRefused(User.Role.VIEWER,
                post("/api/documents/" + ABSENT + "/redact")
                    .contentType(MediaType.APPLICATION_JSON).content(REDACT_BODY),
                "redacting a document");
        }

        @Test
        @DisplayName("cannot flatten annotations into the file")
        void cannotFlatten() throws Exception {
            assertRefused(User.Role.VIEWER,
                post("/api/viewer/" + ABSENT + "/flatten")
                    .contentType(MediaType.APPLICATION_JSON).content(FLATTEN_BODY),
                "flattening annotations");
        }

        @Test
        @DisplayName("cannot rearrange its pages")
        void cannotRearrangePages() throws Exception {
            assertRefused(User.Role.VIEWER,
                post("/api/documents/" + ABSENT + "/pages/arrange")
                    .contentType(MediaType.APPLICATION_JSON).content(ARRANGE_BODY),
                "rearranging pages");
        }

        @Test
        @DisplayName("cannot restore an earlier version over the current one")
        void cannotRestoreAVersion() throws Exception {
            assertRefused(User.Role.VIEWER,
                post("/api/documents/" + ABSENT + "/versions/1/restore"),
                "restoring a version");
        }

        @Test
        @DisplayName("cannot write on a drawing")
        void cannotAnnotate() throws Exception {
            assertRefused(User.Role.VIEWER,
                post("/api/annotations").contentType(MediaType.APPLICATION_JSON)
                    .content(ANNOTATION_BODY),
                "creating an annotation");
        }

        @Test
        @DisplayName("cannot delete somebody else's comment")
        void cannotDeleteAnAnnotation() throws Exception {
            assertRefused(User.Role.VIEWER, delete("/api/annotations/" + ABSENT),
                "deleting an annotation");
        }

        @Test
        @DisplayName("cannot sign a document")
        void cannotSign() throws Exception {
            assertRefused(User.Role.VIEWER,
                post("/api/signatures/document/" + ABSENT + "/sign")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"),
                "signing a document");
        }

        @Test
        @DisplayName("cannot revoke a signature")
        void cannotRevokeASignature() throws Exception {
            assertRefused(User.Role.VIEWER, delete("/api/signatures/" + ABSENT),
                "revoking a signature");
        }

        @Test
        @DisplayName("cannot delete a project and everything in it")
        void cannotDeleteAProject() throws Exception {
            assertRefused(User.Role.VIEWER, delete("/api/projects/" + ABSENT),
                "deleting a project");
        }

        @Test
        @DisplayName("can still read a document")
        void canRead() throws Exception {
            // The other half of the control. A role locked out of everything
            // would pass every case above and be useless.
            assertNotRefused(User.Role.VIEWER, get("/api/documents/" + ABSENT),
                "reading a document");
        }

        @Test
        @DisplayName("can still list a project's documents")
        void canListDocuments() throws Exception {
            assertNotRefused(User.Role.VIEWER, get("/api/documents/project/" + ABSENT),
                "listing a project's documents");
        }

        @Test
        @DisplayName("can still read the markup on one")
        void canReadAnnotations() throws Exception {
            assertNotRefused(User.Role.VIEWER, get("/api/annotations/document/" + ABSENT),
                "reading annotations");
        }
    }

    @Nested
    @DisplayName("an engineer, who originates information")
    class EngineerOriginates {

        @Test
        @DisplayName("may delete a document")
        void mayDelete() throws Exception {
            assertNotRefused(User.Role.ENGINEER, delete("/api/documents/" + ABSENT),
                "deleting a document");
        }

        @Test
        @DisplayName("may redact one")
        void mayRedact() throws Exception {
            assertNotRefused(User.Role.ENGINEER,
                post("/api/documents/" + ABSENT + "/redact")
                    .contentType(MediaType.APPLICATION_JSON).content(REDACT_BODY),
                "redacting a document");
        }

        @Test
        @DisplayName("may write on one")
        void mayAnnotate() throws Exception {
            assertNotRefused(User.Role.ENGINEER,
                post("/api/annotations").contentType(MediaType.APPLICATION_JSON)
                    .content(ANNOTATION_BODY),
                "creating an annotation");
        }

        @Test
        @DisplayName("may sign its own drawing")
        void maySign() throws Exception {
            // A drawing carries "drawn by" as well as "approved by". The
            // division of labour this platform models is drawn at PUBLISH,
            // which this role does not hold — not at the signature, and an
            // earlier draft of these permissions that withheld it here meant
            // an engineer could not sign the drawing they had just produced.
            assertNotRefused(User.Role.ENGINEER,
                post("/api/signatures/document/" + ABSENT + "/sign")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"),
                "signing a document");
        }

        @Test
        @DisplayName("may not publish, which is the authorising act it lacks")
        void mayNotPublish() throws Exception {
            // Where the line actually sits. Kept here because it is the reason
            // the signature is permitted above: if nothing separated this role
            // from a reviewer, the distinction would be decorative.
            assertThat(RolePermissions.grantedTo(User.Role.ENGINEER))
                .doesNotContain(com.cde.platform.cde.domain.ContainerPermission.PUBLISH);
        }
    }

    @Nested
    @DisplayName("a reviewer, who authorises it")
    class ReviewerAuthorises {

        @Test
        @DisplayName("may sign a document")
        void maySign() throws Exception {
            assertNotRefused(User.Role.REVIEWER,
                post("/api/signatures/document/" + ABSENT + "/sign")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"),
                "signing a document");
        }

        @Test
        @DisplayName("may raise and answer comments, which is how a review happens")
        void mayAnnotate() throws Exception {
            assertNotRefused(User.Role.REVIEWER,
                post("/api/annotations").contentType(MediaType.APPLICATION_JSON)
                    .content(ANNOTATION_BODY),
                "creating an annotation");
        }

        @Test
        @DisplayName("may not redact the document it is reviewing")
        void mayNotRedact() throws Exception {
            // A reviewer marks a drawing up and sends it back; it does not
            // alter it. Redaction is originating work.
            assertRefused(User.Role.REVIEWER,
                post("/api/documents/" + ABSENT + "/redact")
                    .contentType(MediaType.APPLICATION_JSON).content(REDACT_BODY),
                "redacting a document");
        }

        @Test
        @DisplayName("may not delete the document it is reviewing")
        void mayNotDelete() throws Exception {
            assertRefused(User.Role.REVIEWER, delete("/api/documents/" + ABSENT),
                "deleting a document");
        }
    }

    @Nested
    @DisplayName("a role with nothing granted")
    class UnknownRole {

        @Test
        @DisplayName("reaches nothing, not even reading")
        void reachesNothing() throws Exception {
            // RolePermissions.grantedTo(null) returns an empty set on purpose,
            // rather than defaulting to the least privileged role — so an
            // account whose role failed to load is refused instead of quietly
            // granted read access.
            assertThat(mockMvc.perform(get("/api/documents/" + ABSENT)
                    .with(user(USERNAME).authorities(java.util.List.of())))
                .andReturn().getResponse().getStatus())
                .isEqualTo(FORBIDDEN);
        }
    }
}
