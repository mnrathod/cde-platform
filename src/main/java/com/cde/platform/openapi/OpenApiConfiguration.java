package com.cde.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.cde.platform.openapi.ApiDocumentation.*;

/**
 * The parts of the OpenAPI document that are not derived from a single
 * endpoint: the description, the security schemes, the tag list, and the error
 * envelope every operation refers to.
 *
 * <p>Everything else is generated from annotations on the controllers and DTOs,
 * which is what keeps the document honest — it is derived from the code, so it
 * cannot describe an endpoint that does not exist or omit one that does (§3.5).
 */
@Configuration
public class OpenApiConfiguration {

    private static final String API_VERSION = "1.0.0";

    private final String applicationVersion;

    public OpenApiConfiguration(@Value("${cde.api.version:" + API_VERSION + "}") String applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    @Bean
    public OpenAPI cdePlatformApi() {
        return new OpenAPI()
            .info(apiInfo())
            .tags(tags())
            .components(new Components().securitySchemes(securitySchemes()))
            // Applied to every operation unless an operation overrides it with
            // its own (empty) requirement. Deny by default carries into the
            // document: an endpoint that forgot to say it is public is
            // described as requiring a credential, not as open.
            .addSecurityItem(new SecurityRequirement().addList(SECURITY_BEARER));
    }

    private Info apiInfo() {
        return new Info()
            .title("CDE Platform API")
            .version(applicationVersion)
            .description(description())
            .license(new License().name("Proprietary"))
            .contact(new Contact().name("CDE Platform engineering"));
    }

    /**
     * The cross-cutting rules a client needs before reading any single
     * operation: how tenancy is decided, what an error looks like, and what
     * happens when a limit is hit.
     */
    private String description() {
        return """
            Common Data Environment API for construction and asset information.

            ## Tenant scoping

            Every request is scoped to exactly one tenant, and the tenant is taken \
            **from the authenticated principal** — never from a path, query, header, or body \
            parameter. There is no tenant selector on any endpoint, and supplying one has \
            no effect. A resource belonging to another tenant is not visible: reading it by \
            id returns `404`, not `403`, so the API does not confirm that an id exists \
            somewhere else.

            ## Errors

            Every error response is an RFC 9457 Problem Details document with the media type \
            `application/problem+json`. It carries a `traceId` that identifies the request in \
            the server logs — quote it when reporting a problem. The `type` member is a stable \
            relative URI identifying the kind of failure, and is safe to branch on when the \
            status code alone is too coarse; `detail` is human-readable text whose wording may \
            change, and is not.

            Validation failures return `422` with an `invalidFields` array naming each \
            rejected field. A request body that cannot be parsed at all returns `400`, \
            because no field can be named.

            ## Rate limits

            Responses carry `X-RateLimit-Limit`, `X-RateLimit-Remaining` and \
            `X-RateLimit-Reset`. A request that exceeds a limit returns `429` with \
            `Retry-After` in seconds. Limits apply per account, per source address and per \
            tenant independently, so a `429` does not necessarily mean this account was the \
            one being noisy.

            ## Tracing

            Every response carries `X-Trace-Id`. Send a W3C `traceparent` header and its \
            trace id is adopted, so a client-side trace and the server-side logs share one \
            identifier.
            """;
    }

    private List<Tag> tags() {
        return List.of(
            tag(TAG_AUTHENTICATION,
                "Registration, sign-in and the session lifecycle."),
            tag(TAG_PROJECTS,
                "Projects group documents and carry the ownership and phase a document inherits."),
            tag(TAG_CDE,
                "Information containers, their revisions and the gated transitions between "
                + "work in progress, shared, published and archived. A container's identity is "
                + "separate from any revision of its content, and a published revision is never "
                + "edited — it is superseded by a new one and archived, with the lineage kept."),
            tag(TAG_DOCUMENTS,
                "Upload, download, metadata and deletion of documents held in a project."),
            tag(TAG_DOCUMENT_VERSIONS,
                "The revision history of a document. Every operation that rewrites a document "
                + "commits a version, and earlier versions stay retrievable."),
            tag(TAG_ANNOTATIONS,
                "Markup laid over a document — shapes, comments, review status and replies."),
            tag(TAG_DOCUMENT_PROCESSING,
                "Operations that rewrite a document: conversion, OCR, redaction, watermarking, "
                + "form filling and flattening. Each commits a new version rather than editing in place."),
            tag(TAG_PAGES,
                "Reordering, rotating, deleting, inserting and extracting pages."),
            tag(TAG_SIGNATURES,
                "PAdES digital signatures applied to a document version, and their verification."),
            tag(TAG_VIEWER,
                "Rendering, text extraction and search for the document viewer."),
            tag(TAG_VIEWER_3D,
                "Model geometry, hierarchy and properties. The structured hierarchy is the "
                + "primary interface; the rendered view is a layer over it."),
            tag(TAG_COMPARISON,
                "Difference between two documents or two versions of one document."),
            tag(TAG_COLLABORATION,
                "Presence and live editing state. The transport is STOMP over WebSocket; these "
                + "endpoints describe the session, not the message stream."),
            tag(TAG_ASSISTANCE,
                "Assisted document understanding. Subject to the per-tenant kill switch and "
                + "disabled by default on sovereign deployments."),
            tag(TAG_DIAGNOSTICS,
                "Client-side error reporting and service status."));
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    private Map<String, SecurityScheme> securitySchemes() {
        return Map.of(
            SECURITY_BEARER, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("""
                    Signed JSON Web Token from `POST /api/auth/login`, sent as \
                    `Authorization: Bearer <token>`. The token carries the subject and the \
                    tenant; the tenant claim is authoritative and is not overridable by any \
                    request parameter."""),

            SECURITY_SESSION_COOKIE, new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("__Host-session")
                .description("""
                    Browser session cookie: `HttpOnly`, `Secure`, `SameSite=Lax`, `__Host-` \
                    prefixed. State-changing requests additionally require a CSRF token."""),

            SECURITY_API_KEY, new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-API-Key")
                .description("""
                    Machine credential, sent as `X-API-Key`. Shown once at creation and stored \
                    only as a SHA-256 digest, so a lost key is replaced rather than recovered. \
                    Scoped, expiring, and rate-limited per key."""),

            SECURITY_OAUTH2, new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("""
                    Authorization code with PKCE for user-facing clients; client credentials \
                    for machine clients. The implicit flow is not offered.""")
                .flows(new OAuthFlows()
                    .authorizationCode(new OAuthFlow()
                        .authorizationUrl("/oauth2/authorize")
                        .tokenUrl("/oauth2/token")
                        .refreshUrl("/oauth2/token")
                        .scopes(scopes()))
                    .clientCredentials(new OAuthFlow()
                        .tokenUrl("/oauth2/token")
                        .scopes(scopes()))));
    }

    private Scopes scopes() {
        return new Scopes()
            .addString("project:read", "List and read projects")
            .addString("project:write", "Create, update and delete projects")
            .addString("document:read", "Read document metadata and content")
            .addString("document:write", "Upload, replace and delete documents")
            .addString("document:process", "Run operations that rewrite a document")
            .addString("annotation:read", "Read markup on a document")
            .addString("annotation:write", "Create, update and resolve markup")
            .addString("signature:read", "Read and verify digital signatures")
            .addString("signature:write", "Apply a digital signature to a document version");
    }

    /**
     * Documents the error envelope, including the members Spring's
     * {@code ProblemDetail} carries in its extension map and therefore cannot
     * describe from its own type.
     *
     * <p>The member names come from {@link ApiDocumentation}, the same
     * constants {@code ApiProblem} sets them with, and
     * {@code ProblemDetailContractTest} asserts that a real error response
     * carries exactly what is documented here. A member added to one and not
     * the other fails the build rather than quietly under-describing the API.
     */
    @Bean
    public OpenApiCustomizer problemDetailSchemaCustomizer() {
        return openApi -> {
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
            openApi.getComponents().addSchemas(ASSISTANT_PAYLOAD_SCHEMA, assistantPayloadSchema());
            // ModelTreeNode is referenced only from an @ApiResponse on a
            // method whose return type is erased, so nothing walks it into
            // components and the reference dangles. Registered explicitly.
            openApi.getComponents().addSchemas("ModelTreeNode",
                new io.swagger.v3.core.converter.ModelConverters().read(
                    com.cde.platform.dto.ViewerDtos.ModelTreeNode.class).get("ModelTreeNode"));
        };
    }

    /**
     * The assistant endpoints forward the model provider's own formats without
     * interpreting them, so what they accept and return is whatever that
     * provider defines. Describing it as a free-form object says exactly that;
     * enumerating members would claim a stability this API does not control.
     */
    private Schema<Object> assistantPayloadSchema() {
        ObjectSchema payload = new ObjectSchema();
        payload.description("""
            The model provider's own message format, passed through unchanged in both \
            directions. Its members are defined by the provider, not by this API, and can \
            change when the provider's version changes — so this API does not enumerate them \
            and does not validate them.""");
        payload.additionalProperties(true);
        return payload;
    }

    private Schema<Object> problemSchema() {
        ObjectSchema invalidField = new ObjectSchema();
        invalidField.description("One field the request was rejected for.");
        invalidField.addProperty("field", new StringSchema()
            .description("Path of the rejected field, as it appears in the request body.")
            .example("name"));
        invalidField.addProperty("message", new StringSchema()
            .description("What is wrong with the value, phrased for the person who typed it.")
            .example("must not be blank"));
        invalidField.required(List.of("field", "message"));

        ArraySchema invalidFields = new ArraySchema();
        invalidFields.items(invalidField);
        invalidFields.description("""
            Present on `422` validation failures. Names every field that was rejected, so a \
            client can attach each message to the control it belongs to rather than to the \
            form as a whole.""");

        ObjectSchema problem = new ObjectSchema();
        problem.description("""
            RFC 9457 Problem Details. The single error shape returned by every endpoint, for \
            every status.""");
        problem.addProperty("type", new StringSchema()
            .format("uri-reference")
            .description("""
                Stable identifier for this kind of problem, relative to the API host. Safe to \
                branch on — unlike `detail`, its value does not change with wording.""")
            .example("/problems/validation-failed"));
        problem.addProperty("title", new StringSchema()
            .description("Short summary of the problem type. Does not vary between occurrences.")
            .example("Validation failed"));
        problem.addProperty("status", new IntegerSchema()
            .format("int32")
            .minimum(BigDecimal.valueOf(400))
            .maximum(BigDecimal.valueOf(599))
            .description("HTTP status code, repeated here so a logged body is self-contained.")
            .example(422));
        problem.addProperty("detail", new StringSchema()
            .description("""
                What happened and what to do next, written for a person to read. Never contains \
                a stack trace, SQL, or an internal type name.""")
            .example("1 field is invalid."));
        problem.addProperty("instance", new StringSchema()
            .format("uri-reference")
            .description("Path of the request that failed.")
            .example("/api/projects"));
        problem.addProperty(PROBLEM_TRACE_ID, new StringSchema()
            .pattern("^[0-9a-f]{32}$")
            .description("""
                Identifies this request in the server logs, and is also returned in the \
                `X-Trace-Id` header. Quote it when reporting a problem.""")
            .example("4f8a1c2e9b7d6a5f3e2d1c0b9a8f7e6d"));
        problem.addProperty(PROBLEM_TIMESTAMP, new StringSchema()
            .format("date-time")
            .description("When the failure was recorded, UTC.")
            .example("2026-02-17T09:41:12.884Z"));
        problem.addProperty(PROBLEM_INVALID_FIELDS, invalidFields);
        problem.required(List.of("type", "title", "status", "detail", PROBLEM_TRACE_ID));
        return problem;
    }
}
