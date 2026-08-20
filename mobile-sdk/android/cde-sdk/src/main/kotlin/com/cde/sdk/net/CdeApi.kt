package com.cde.sdk.net

import com.cde.sdk.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Everything that can go wrong talking to the CDE, as one type.
 *
 * Callers should not switch on HTTP status codes — the mapping from status to
 * meaning belongs here, once, so a caller handles `Unauthenticated` rather
 * than remembering that 401 and a 403 on a refreshed token mean different
 * things.
 */
sealed class CdeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No usable token. The SDK has already cleared it; sign in again. */
    class Unauthenticated(message: String = "Sign in again to continue.") : CdeError(message)

    /** Authenticated, but this account may not do it. */
    class Forbidden(message: String = "You do not have permission to do that.") : CdeError(message)

    class NotFound(message: String = "That document is no longer available.") : CdeError(message)

    /** The server rejected the request and said why. Safe to show. */
    class Rejected(message: String) : CdeError(message)

    /**
     * Reachable but temporarily unable — the converter being down, most often.
     * Distinguished from [Offline] because retrying is worth it but caching is
     * not the answer.
     */
    class Unavailable(message: String = "The service is busy. Try again shortly.") : CdeError(message)

    /** No usable network. The offline cache should answer instead. */
    class Offline(cause: Throwable? = null) :
        CdeError("No connection.", cause)

    class Unexpected(message: String, cause: Throwable? = null) : CdeError(message, cause)
}

/** Where the SDK talks to, and how patiently. */
data class CdeConfiguration(
    val baseUrl: String,
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 30,
)

/**
 * The HTTP surface of the CDE.
 *
 * Every call suspends and runs on the IO dispatcher; none of them touch the
 * main thread. Responses are decoded leniently — `ignoreUnknownKeys` is
 * deliberate, so a field added to the server does not break every installed
 * copy of the app in the field, which is the failure mode that matters when
 * clients are on phones you cannot update on demand.
 */
class CdeApi(
    private val configuration: CdeConfiguration,
    private val tokenProvider: () -> String?,
    private val onUnauthenticated: () -> Unit,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(configuration.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(configuration.readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    // ── Authentication ───────────────────────────────────────────

    suspend fun login(username: String, password: String): AuthResult {
        val body = json.encodeToString(
            LoginRequest.serializer(), LoginRequest(username, password))
        val response = post("/api/auth/login", body, authenticated = false)
        return json.decodeFromString(AuthResult.serializer(), response)
    }

    // ── Projects and documents ───────────────────────────────────

    suspend fun projects(): List<Project> =
        json.decodeFromString(get("/api/projects"))

    suspend fun documents(projectId: Long): List<CdeDocument> =
        json.decodeFromString(get("/api/documents/project/$projectId"))

    suspend fun document(documentId: Long): CdeDocument =
        json.decodeFromString(get("/api/documents/$documentId"))

    /**
     * How to open a document.
     *
     * The response is polymorphic on `type`, so it is decoded by hand into the
     * sealed [ViewerSource] rather than into a struct of optionals — the
     * branch is taken once, here, instead of at every use.
     */
    suspend fun viewerSource(documentId: Long): ViewerSource {
        val payload = json.decodeFromString<JsonObject>(get("/api/viewer/$documentId"))
        fun str(key: String): String? =
            payload[key]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() }

        val name = str("name") ?: "Untitled"
        val revision = str("revision")
        val drawingNumber = str("drawingNumber")

        return when (val type = str("type")) {
            "pdf" -> ViewerSource.Pdf(
                name = name, revision = revision, drawingNumber = drawingNumber,
                fileName = str("fileName"),
                pdfPath = str("pdfUrl") ?: "/api/viewer/$documentId/pdf",
                version = payload["version"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 1,
            )

            "svg" -> ViewerSource.Drawing(
                name = name, revision = revision, drawingNumber = drawingNumber,
                svg = str("content").orEmpty(),
            )

            "image" -> ViewerSource.Image(
                name = name, revision = revision, drawingNumber = drawingNumber,
                imagePath = str("imageUrl") ?: "/api/viewer/$documentId/pdf",
            )

            else -> ViewerSource.Unsupported(
                name = name, revision = revision, drawingNumber = drawingNumber,
                type = type ?: "unknown", fileName = str("fileName"),
            )
        }
    }

    /** Raw PDF bytes. Streamed to a file by the cache rather than held whole. */
    suspend fun pdfBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        execute(request(path).build()) { it.body?.bytes() ?: ByteArray(0) }
    }

    // ── Annotations ──────────────────────────────────────────────

    suspend fun annotations(documentId: Long): List<Annotation> =
        json.decodeFromString(get("/api/annotations/document/$documentId"))

    suspend fun createAnnotation(request: AnnotationRequest): Annotation {
        val body = json.encodeToString(AnnotationRequest.serializer(), request)
        return json.decodeFromString(post("/api/annotations", body))
    }

    suspend fun updateAnnotation(id: Long, request: AnnotationRequest): Annotation {
        val body = json.encodeToString(AnnotationRequest.serializer(), request)
        return json.decodeFromString(send("PUT", "/api/annotations/$id", body))
    }

    suspend fun resolveAnnotation(id: Long): Annotation =
        json.decodeFromString(send("PATCH", "/api/annotations/$id/resolve", "{}"))

    suspend fun deleteAnnotation(id: Long) {
        send("DELETE", "/api/annotations/$id", null)
    }

    // ── Plumbing ─────────────────────────────────────────────────

    private fun request(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path
                  else configuration.baseUrl.trimEnd('/') + path
        val builder = Request.Builder().url(url)
        tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        execute(request(path).build()) { it.body?.string().orEmpty() }
    }

    private suspend fun post(path: String, body: String, authenticated: Boolean = true): String =
        send("POST", path, body, authenticated)

    private suspend fun send(
        method: String,
        path: String,
        body: String?,
        authenticated: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val builder = if (authenticated) request(path)
                      else Request.Builder().url(configuration.baseUrl.trimEnd('/') + path)
        val payload = body?.toRequestBody("application/json".toMediaType())
        execute(builder.method(method, payload).build()) { it.body?.string().orEmpty() }
    }

    private fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            // No route to the server at all. Distinguished from a server that
            // answered badly, because the offline cache can serve this case
            // and cannot serve the other.
            throw CdeError.Offline(e)
        }

        response.use {
            if (it.isSuccessful) return read(it)

            val message = serverMessage(it)
            throw when (it.code) {
                401 -> { onUnauthenticated(); CdeError.Unauthenticated() }
                403 -> CdeError.Forbidden(message ?: "You do not have permission to do that.")
                404 -> CdeError.NotFound(message ?: "That document is no longer available.")
                400, 409, 422 -> CdeError.Rejected(message ?: "The server rejected that request.")
                503 -> CdeError.Unavailable(message ?: "The service is busy. Try again shortly.")
                else -> CdeError.Unexpected(message ?: "Something went wrong (${it.code}).")
            }
        }
    }

    /**
     * The server writes `{ "message": ... }` for text meant to be read by a
     * person. Anything else is discarded rather than shown: a stack trace or
     * an internal identifier in front of a user is both unhelpful and a
     * disclosure.
     */
    private fun serverMessage(response: okhttp3.Response): String? = runCatching {
        val body = response.peekBody(8 * 1024).string()
        json.decodeFromString<JsonObject>(body)["message"]?.jsonPrimitive?.contentOrNull()
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}

@kotlinx.serialization.Serializable
internal data class LoginRequest(val username: String, val password: String)

@kotlinx.serialization.Serializable
data class AuthResult(val token: String, val username: String, val role: String)
