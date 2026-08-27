package com.cde.platform.openapi;

/**
 * Names shared between the annotations on the controllers and the OpenAPI
 * document that describes them.
 *
 * <p>Tags, security scheme names and problem-type slugs are all referenced from
 * two places — the annotation on the endpoint, and the configuration that gives
 * the tag its description or declares the scheme. Spelling either of them as a
 * string literal in both places is how a tag ends up with no description and an
 * endpoint ends up in an undocumented group, neither of which fails anything
 * until someone reads the published docs.
 */
public final class ApiDocumentation {

    private ApiDocumentation() {
    }

    // ── Tags ─────────────────────────────────────────────────────────────────

    public static final String TAG_AUTHENTICATION = "Authentication";
    public static final String TAG_TENANT_MEMBERSHIP = "Organisation membership";
    public static final String TAG_PROJECTS = "Projects";
    public static final String TAG_CDE = "Common Data Environment";
    public static final String TAG_DOCUMENTS = "Documents";
    public static final String TAG_DOCUMENT_VERSIONS = "Document versions";
    public static final String TAG_ANNOTATIONS = "Annotations";
    public static final String TAG_DOCUMENT_PROCESSING = "Document processing";
    public static final String TAG_PAGES = "Page manipulation";
    public static final String TAG_SIGNATURES = "Digital signatures";
    public static final String TAG_VIEWER = "Viewer";
    public static final String TAG_VIEWER_3D = "3D and model viewer";
    public static final String TAG_COMPARISON = "Document comparison";
    public static final String TAG_COLLABORATION = "Collaboration";
    public static final String TAG_ASSISTANCE = "Assistance";
    public static final String TAG_AUDIT = "Audit trail";
    public static final String TAG_DIAGNOSTICS = "Diagnostics";

    // ── Security schemes ─────────────────────────────────────────────────────

    /** Bearer JWT, the scheme every authenticated endpoint uses today. */
    public static final String SECURITY_BEARER = "bearerAuth";

    /** Session cookie, for browser clients once §4.6 cookie sessions land. */
    public static final String SECURITY_SESSION_COOKIE = "sessionCookie";

    /** Long-lived machine credential, per §4.6. */
    public static final String SECURITY_API_KEY = "apiKey";

    /** OAuth 2.0, authorization code + PKCE for users, client credentials for machines. */
    public static final String SECURITY_OAUTH2 = "oauth2";

    // ── The shared error envelope ────────────────────────────────────────────

    /**
     * The name under which the RFC 9457 envelope appears in
     * {@code components/schemas}. Every documented error response references
     * it; nothing else describes an error body.
     */
    public static final String PROBLEM_SCHEMA = "ProblemDetail";

    public static final String PROBLEM_REF = "#/components/schemas/" + PROBLEM_SCHEMA;

    public static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    /**
     * The model provider's own request and reply formats, carried through
     * unchanged. Named so the assistant endpoints reference one described
     * schema rather than an empty inline object, which is what an untyped
     * passthrough generates.
     */
    public static final String ASSISTANT_PAYLOAD_SCHEMA = "AssistantPayload";

    public static final String ASSISTANT_PAYLOAD_REF =
        "#/components/schemas/" + ASSISTANT_PAYLOAD_SCHEMA;

    // ── Extension members of the envelope ────────────────────────────────────
    //
    // ApiProblem sets these; OpenApiConfiguration documents them. Shared so the
    // documented shape and the returned shape cannot disagree — ProblemDetailContractTest
    // asserts that they do not.

    public static final String PROBLEM_TRACE_ID = "traceId";
    public static final String PROBLEM_TIMESTAMP = "timestamp";
    public static final String PROBLEM_INVALID_FIELDS = "invalidFields";
}
