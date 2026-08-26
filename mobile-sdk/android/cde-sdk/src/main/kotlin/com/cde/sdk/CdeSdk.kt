package com.cde.sdk

import android.content.Context
import com.cde.sdk.auth.TokenStore
import com.cde.sdk.model.*
import com.cde.sdk.net.AuthResult
import com.cde.sdk.net.CdeApi
import com.cde.sdk.net.CdeConfiguration
import com.cde.sdk.net.CdeError
import com.cde.sdk.offline.OfflineStore
import com.cde.sdk.offline.PendingChange
import com.cde.sdk.offline.SyncEngine
import com.cde.sdk.render.PdfPageRenderer
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The entry point to the CDE SDK.
 *
 * One instance per signed-in user, held for the life of the app:
 *
 * ```kotlin
 * val cde = CdeSdk(context, CdeConfiguration(baseUrl = "https://cde.example.com"))
 * cde.signIn("username", "password")
 * val documents = cde.documents(projectId = 1).content
 * ```
 *
 * Everything that touches the network suspends and answers from the offline
 * cache when there is none, so a caller does not branch on connectivity —
 * the alternative is every screen in the host app reimplementing that choice,
 * differently.
 */
class CdeSdk(
    context: Context,
    configuration: CdeConfiguration,
) {
    private val applicationContext = context.applicationContext
    private val tokens = TokenStore(applicationContext)
    private val store = OfflineStore(applicationContext)

    private val api = CdeApi(
        configuration = configuration,
        tokenProvider = { tokens.token },
        onUnauthenticated = { tokens.clear() },
    )

    private val sync = SyncEngine(api, store)

    /** Queue depth and progress, for a status indicator in the host app. */
    val syncState: StateFlow<com.cde.sdk.offline.SyncState> get() = sync.state

    val isSignedIn: Boolean get() = tokens.token != null
    val signedInAs: String? get() = tokens.username

    // ── Session ──────────────────────────────────────────────────

    suspend fun signIn(username: String, password: String): AuthResult {
        val result = api.login(username, password)
        tokens.store(result.token, result.username, result.role)
        return result
    }

    /**
     * Ends the session.
     *
     * Cached documents go; the outbound queue does not, unless
     * [discardPendingChanges] is set. Markup someone drew on site exists
     * nowhere else, and signing out — often just to switch account — is not
     * a statement that they wanted to throw it away.
     */
    fun signOut(discardPendingChanges: Boolean = false) {
        tokens.clear()
        store.clearCachedContent()
        if (discardPendingChanges) store.replace(emptyList())
    }

    // ── Browsing ─────────────────────────────────────────────────

    suspend fun projects(): List<Project> = api.projects()

    /**
     * One page of a project's documents, falling back to what was last seen
     * when there is no connection.
     *
     * <p>The cache holds the first page only, and the page returned offline
     * describes itself as exactly that — the totals are the cache's own, not a
     * remembered server count, so a caller is never told there are 137
     * documents when it can produce 50.
     */
    suspend fun documents(projectId: Long, page: Int = 0, size: Int = 50): DocumentPage = try {
        api.documents(projectId, page, size)
            .also { if (it.number == 0) store.storeDocuments(projectId, it.content) }
    } catch (e: CdeError.Offline) {
        val cached = if (page == 0) store.cachedDocuments(projectId) else emptyList()
        DocumentPage(
            content = cached, number = page, size = size,
            totalElements = cached.size.toLong(), totalPages = 1,
            first = page == 0, last = true)
    }

    // ── Opening a document ───────────────────────────────────────

    /**
     * How to render a document, and — for a PDF — the file to render from,
     * downloaded if it is not already cached.
     *
     * @param prefetch when true the PDF is fetched even if the caller only
     *        meant to inspect the type. Left false so a document list can ask
     *        cheaply what something is.
     */
    suspend fun open(documentId: Long, prefetch: Boolean = true): OpenedDocument {
        val source = api.viewerSource(documentId)
        return when (source) {
            is ViewerSource.Pdf -> {
                // The URL carries ?v=, so it is also the cache key: a new
                // version cannot be served from an old file.
                val cached = store.cachedFile(source.pdfPath)
                val file = when {
                    cached != null -> cached
                    prefetch -> store.storeFile(source.pdfPath, api.pdfBytes(source.pdfPath))
                    else -> null
                }
                OpenedDocument.Pdf(source, file)
            }

            is ViewerSource.Drawing -> OpenedDocument.Drawing(source)
            is ViewerSource.Image -> OpenedDocument.Image(source)
            is ViewerSource.Unsupported -> OpenedDocument.Unsupported(source)
        }
    }

    /** Opens a cached PDF for rendering. Caller closes it. */
    fun renderer(file: File): PdfPageRenderer = PdfPageRenderer.open(file)

    // ── Annotations ──────────────────────────────────────────────

    suspend fun annotations(documentId: Long): List<CdeAnnotation> = sync.annotations(documentId)

    /**
     * Records markup.
     *
     * Queued locally first and sent when possible, so the call succeeds with
     * no signal and the drawing is never lost to a failed request. The
     * returned annotation carries a negative id until the server assigns a
     * real one — callers should key on `shapeData.id`, which is stable from
     * the moment it is drawn.
     */
    suspend fun addAnnotation(
        documentId: Long,
        shape: ShapeData,
        type: AnnotationType = AnnotationType.MARKUP,
        comment: String? = null,
    ): CdeAnnotation {
        val request = AnnotationRequest(
            documentId = documentId,
            type = type,
            shapeData = MarkupCodec.encode(shape),
            comment = comment,
            pageNumber = shape.pageNumber,
        )
        store.enqueue(PendingChange.Create(localId = shape.id, request = request))
        sync.sync()
        return CdeAnnotation(
            id = -1, documentId = documentId, type = type,
            shapeData = request.shapeData, comment = comment,
            pageNumber = shape.pageNumber,
        )
    }

    suspend fun resolveAnnotation(annotationId: Long) {
        store.enqueue(PendingChange.Resolve(
            localId = "resolve-$annotationId", annotationId = annotationId))
        sync.sync()
    }

    suspend fun deleteAnnotation(annotationId: Long) {
        store.enqueue(PendingChange.Delete(
            localId = "delete-$annotationId", annotationId = annotationId))
        sync.sync()
    }

    /**
     * Sends anything queued. Call on app resume and when connectivity
     * returns; it is safe to call more often than necessary.
     *
     * @return changes the server refused outright, which will not be retried
     */
    suspend fun synchronise(): List<SyncEngine.RejectedChange> = sync.sync()

    // ── Storage ──────────────────────────────────────────────────

    fun cacheSizeBytes(): Long = store.cacheSizeBytes()

    fun trimCacheTo(limitBytes: Long) = store.trimTo(limitBytes)
}

/** A document ready to render, with the branch already taken. */
sealed interface OpenedDocument {
    val source: ViewerSource

    data class Pdf(
        override val source: ViewerSource.Pdf,
        /** Null only when opened with `prefetch = false` and not cached. */
        val file: File?,
    ) : OpenedDocument

    data class Drawing(override val source: ViewerSource.Drawing) : OpenedDocument
    data class Image(override val source: ViewerSource.Image) : OpenedDocument
    data class Unsupported(override val source: ViewerSource.Unsupported) : OpenedDocument
}
