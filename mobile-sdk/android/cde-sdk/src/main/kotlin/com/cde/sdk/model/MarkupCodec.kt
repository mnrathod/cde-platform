package com.cde.sdk.model

import kotlinx.serialization.json.Json

/**
 * Reads and writes the `shapeData` string.
 *
 * The server stores this verbatim and never parses it, so nothing on the wire
 * enforces its shape. That makes this the single place where the mobile
 * clients and the web viewer agree, and the only place a mismatch could be
 * introduced — hence one codec rather than ad-hoc serialisation at call sites.
 */
object MarkupCodec {

    private val json = Json {
        ignoreUnknownKeys = true   // a field added by the web viewer must not break parsing
        encodeDefaults = false     // keep the payload close to what the web writes
        explicitNulls = false
    }

    fun encode(shape: ShapeData): String = json.encodeToString(ShapeData.serializer(), shape)

    /**
     * Parses stored markup, returning null rather than throwing.
     *
     * An annotation written by a future client, or corrupted in transit, is
     * one annotation that cannot be drawn — not a reason to fail loading the
     * document and hide every other annotation on it.
     */
    fun decode(shapeData: String): ShapeData? =
        runCatching { json.decodeFromString(ShapeData.serializer(), shapeData) }.getOrNull()

    /** Decodes a list, dropping any entry that cannot be read. */
    fun decodeAll(annotations: List<Annotation>): List<ShapeData> =
        annotations.mapNotNull { decode(it.shapeData)?.copy(savedId = it.id) }
}
