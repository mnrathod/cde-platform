package com.cde.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A named category of sensitive value that redaction knows how to find.
 *
 * <p>An enum rather than a free string, because the conversion service matches
 * a preset by name and silently finds nothing when the name is not one it
 * knows. A redaction that reports success having redacted nothing is the worst
 * possible outcome for this feature — the document goes out believing it was
 * cleaned. Rejecting an unknown name at the boundary turns that into a `400`
 * the caller can see.
 *
 * <p>The patterns themselves live in the conversion service. This type is the
 * agreed vocabulary between the two, so adding a category means adding it in
 * both places — which is the point: it cannot be added in only one.
 */
@Schema(name = "RedactionPreset",
        description = "A named category of sensitive value that redaction can find on its own.")
public enum RedactionPreset {

    @Schema(description = "Email addresses.")
    email,

    @Schema(description = "Telephone numbers.")
    phone,

    @Schema(description = "Payment card numbers.")
    creditCard,

    @Schema(description = "US Social Security numbers.")
    ssn,

    @Schema(description = "UK National Insurance numbers.")
    niNumber,

    @Schema(description = "UK postcodes.")
    postcode,

    @Schema(description = "International bank account numbers.")
    iban
}
