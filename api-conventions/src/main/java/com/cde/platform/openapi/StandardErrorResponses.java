package com.cde.platform.openapi;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The error responses every authenticated endpoint can return, declared once.
 *
 * <p>§3.5 requires every operation to document every status it can produce,
 * each referencing the shared {@code ProblemDetail} schema. Spelled out per
 * endpoint that is eight annotation blocks on each of fifty-odd operations,
 * and the ones that get forgotten are the ones nobody was thinking about —
 * which are exactly the ones a client most needs documented.
 *
 * <p>Statuses that depend on the individual endpoint — {@code 404} for anything
 * addressing a resource by id, {@code 409} for anything that can collide,
 * {@code 422} for anything with a body to validate — are declared on the
 * operation itself, because they are genuinely per-operation facts rather than
 * boilerplate.
 *
 * <p>{@code 422} is deliberately not here even though most operations have one.
 * Declaring it in both places produced two responses for one status, and which
 * description survived the merge varied between runs — so the generated
 * document was not reproducible, and the gate that compares it against the
 * committed copy would have failed at random.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
@ApiResponse(
    responseCode = "400",
    description = "The request could not be read — malformed JSON, or a value outside an "
                + "accepted set. No field can be named, because parsing did not get that far.",
    content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                       schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
@ApiResponse(
    responseCode = "401",
    description = "No credential was presented, or the one presented is expired or invalid.",
    content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                       schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
@ApiResponse(
    responseCode = "403",
    description = "Authenticated, but without the permission this operation requires.",
    content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                       schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
@ApiResponse(
    responseCode = "429",
    description = "A rate limit was exceeded. `Retry-After` gives the seconds to wait. Limits "
                + "apply per account, per source address and per tenant independently.",
    content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                       schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
@ApiResponse(
    responseCode = "500",
    description = "The request failed for a reason the caller cannot act on. Quote the "
                + "`traceId` when reporting it.",
    content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                       schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
public @interface StandardErrorResponses {
}
