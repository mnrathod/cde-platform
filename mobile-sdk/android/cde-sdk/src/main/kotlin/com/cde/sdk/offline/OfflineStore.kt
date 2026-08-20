package com.cde.sdk.offline

import android.content.Context
import com.cde.sdk.model.AnnotationRequest
import com.cde.sdk.model.CdeAnnotation
import com.cde.sdk.model.CdeDocument
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A change made on this device that the server has not accepted yet.
 *
 * Queued rather than applied optimistically-and-forgotten: a site visit can
 * produce an hour of markup with no signal, and losing it because a request
 * failed once is not recoverable by the person who drew it.
 */
@Serializable
sealed interface PendingChange {
    val localId: String
    /** Attempts so far. Used to back off, and to stop retrying forever. */
    val attempts: Int

    @Serializable
    data class Create(
        override val localId: String,
        val request: AnnotationRequest,
        override val attempts: Int = 0,
    ) : PendingChange

    @Serializable
    data class Update(
        override val localId: String,
        val annotationId: Long,
        val request: AnnotationRequest,
        override val attempts: Int = 0,
    ) : PendingChange

    @Serializable
    data class Delete(
        override val localId: String,
        val annotationId: Long,
        override val attempts: Int = 0,
    ) : PendingChange

    @Serializable
    data class Resolve(
        override val localId: String,
        val annotationId: Long,
        override val attempts: Int = 0,
    ) : PendingChange
}

/**
 * What a document looks like on disk between sessions.
 *
 * Files are keyed by the URL the server gave, which already carries `?v=` — so
 * a new version is a new key and a stale copy can never be served for it. That
 * is why there is no expiry check here: freshness is the server's statement,
 * not this cache's guess.
 */
class OfflineStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root = File(context.filesDir, "cde-sdk").apply { mkdirs() }
    private val files = File(root, "documents").apply { mkdirs() }
    private val metadata = File(root, "metadata").apply { mkdirs() }
    private val queue = File(root, "pending.json")

    // ── Document bytes ───────────────────────────────────────────

    fun cachedFile(key: String): File? =
        File(files, digest(key)).takeIf { it.exists() && it.length() > 0 }

    /**
     * Writes through a temporary file and renames, so a download interrupted
     * by the process dying leaves no half-file that would later be served as
     * a whole document.
     */
    fun storeFile(key: String, bytes: ByteArray): File {
        val target = File(files, digest(key))
        val temporary = File(files, digest(key) + ".part")
        temporary.writeBytes(bytes)
        if (target.exists()) target.delete()
        temporary.renameTo(target)
        return target
    }

    // ── Document metadata and annotations ────────────────────────

    fun storeDocuments(projectId: Long, documents: List<CdeDocument>) {
        File(metadata, "project-$projectId.json")
            .writeText(json.encodeToString(documents))
    }

    fun cachedDocuments(projectId: Long): List<CdeDocument> =
        File(metadata, "project-$projectId.json")
            .takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<List<CdeDocument>>(it.readText()) }.getOrNull() }
            ?: emptyList()

    fun storeAnnotations(documentId: Long, annotations: List<CdeAnnotation>) {
        File(metadata, "annotations-$documentId.json")
            .writeText(json.encodeToString(annotations))
    }

    fun cachedAnnotations(documentId: Long): List<CdeAnnotation> =
        File(metadata, "annotations-$documentId.json")
            .takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<List<CdeAnnotation>>(it.readText()) }.getOrNull() }
            ?: emptyList()

    // ── The outbound queue ───────────────────────────────────────

    @Synchronized
    fun pending(): List<PendingChange> =
        queue.takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<List<PendingChange>>(it.readText()) }.getOrNull() }
            ?: emptyList()

    @Synchronized
    fun enqueue(change: PendingChange) {
        writeQueue(pending() + change)
    }

    @Synchronized
    fun replace(changes: List<PendingChange>) = writeQueue(changes)

    @Synchronized
    fun remove(localId: String) {
        writeQueue(pending().filterNot { it.localId == localId })
    }

    private fun writeQueue(changes: List<PendingChange>) {
        queue.writeText(json.encodeToString(changes))
    }

    // ── Housekeeping ─────────────────────────────────────────────

    /** Total bytes held, so the host app can show and bound it. */
    fun cacheSizeBytes(): Long = files.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Drops cached document bytes, oldest first, until under [limitBytes].
     *
     * Only the files go. Metadata and the pending queue are never evicted:
     * they are small, and the queue holds work that exists nowhere else.
     */
    fun trimTo(limitBytes: Long) {
        var total = cacheSizeBytes()
        if (total <= limitBytes) return
        files.listFiles()
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                if (total <= limitBytes) return
                total -= file.length()
                file.delete()
            }
    }

    /** Everything except the pending queue, which would lose unsent work. */
    fun clearCachedContent() {
        files.deleteRecursively(); files.mkdirs()
        metadata.deleteRecursively(); metadata.mkdirs()
    }

    private fun digest(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
