package com.cde.sdk.offline

import com.cde.sdk.model.CdeAnnotation
import com.cde.sdk.net.CdeApi
import com.cde.sdk.net.CdeError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the host app shows about sync state. */
data class SyncState(
    val pendingCount: Int = 0,
    val syncing: Boolean = false,
    /** Set when the last attempt failed for a reason worth telling the user. */
    val lastError: String? = null,
)

/**
 * Drains the offline queue to the server.
 *
 * The rules it follows, and why:
 *
 * **Order is preserved.** Changes are replayed oldest first and a failure
 * stops the run rather than skipping ahead. An update that arrived before its
 * create would be rejected, and a delete replayed out of order would resurrect
 * markup someone deliberately removed.
 *
 * **A rejection is not a retry.** If the server refuses a change on its
 * merits — 400, 403, 404 — replaying it will never succeed, so it is dropped
 * from the queue and reported. Only transport failures and 503s are retried,
 * because only those are about the moment rather than the content.
 *
 * **Deleting something already gone is success.** A 404 on a delete means the
 * intent has been achieved, however it happened; treating it as failure would
 * wedge the queue behind a change that can never complete.
 *
 * **Last write wins, and the writer is told.** The server holds no version on
 * an annotation, so there is nothing to merge against. Rather than pretend to
 * resolve a conflict the data cannot express, an update is applied as-is and
 * the reconciled annotation is returned so the caller sees what actually
 * landed.
 */
class SyncEngine(
    private val api: CdeApi,
    private val store: OfflineStore,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    /** Server ids assigned to shapes created offline, keyed by local id. */
    private val resolvedIds = mutableMapOf<String, Long>()

    init {
        _state.value = SyncState(pendingCount = store.pending().size)
    }

    /**
     * Attempts to send everything queued.
     *
     * Safe to call often — concurrent calls are serialised, so a screen that
     * syncs on resume and a background job that syncs on a network callback
     * cannot double-send.
     *
     * @return the changes that were rejected outright and dropped
     */
    suspend fun sync(): List<RejectedChange> = mutex.withLock {
        val rejected = mutableListOf<RejectedChange>()
        _state.value = _state.value.copy(syncing = true, lastError = null)

        try {
            var queue = store.pending()
            while (queue.isNotEmpty()) {
                val change = queue.first()
                when (val outcome = attempt(change)) {
                    Outcome.Sent -> {
                        store.remove(change.localId)
                        queue = queue.drop(1)
                    }

                    is Outcome.Rejected -> {
                        // Will never succeed. Drop it so the queue can drain,
                        // and hand it back so the user learns their change did
                        // not land rather than silently losing it.
                        store.remove(change.localId)
                        rejected += RejectedChange(change, outcome.reason)
                        queue = queue.drop(1)
                    }

                    is Outcome.Retry -> {
                        // Transport or server availability. Leave the queue
                        // intact and stop: order matters more than progress.
                        store.replace(queue.map { it.withAttempt() })
                        _state.value = _state.value.copy(lastError = outcome.reason)
                        return@withLock rejected
                    }
                }
            }
        } finally {
            _state.value = SyncState(
                pendingCount = store.pending().size,
                syncing = false,
                lastError = _state.value.lastError,
            )
        }
        rejected
    }

    private suspend fun attempt(change: PendingChange): Outcome = try {
        when (change) {
            is PendingChange.Create -> {
                val saved = api.createAnnotation(change.request)
                resolvedIds[change.localId] = saved.id
                Outcome.Sent
            }

            is PendingChange.Update -> {
                api.updateAnnotation(resolve(change.annotationId, change.localId), change.request)
                Outcome.Sent
            }

            is PendingChange.Resolve -> {
                api.resolveAnnotation(resolve(change.annotationId, change.localId))
                Outcome.Sent
            }

            is PendingChange.Delete -> {
                api.deleteAnnotation(resolve(change.annotationId, change.localId))
                Outcome.Sent
            }
        }
    } catch (e: CdeError.NotFound) {
        // Already gone. For a delete that is the desired end state; for the
        // others the target no longer exists and replaying cannot fix it.
        if (change is PendingChange.Delete) Outcome.Sent
        else Outcome.Rejected(e.message ?: "That item no longer exists.")
    } catch (e: CdeError.Offline) {
        Outcome.Retry("Waiting for a connection.")
    } catch (e: CdeError.Unavailable) {
        // Back off a little so a busy server is not hammered by every device.
        delay(backoff(change.attempts))
        Outcome.Retry(e.message ?: "The service is busy.")
    } catch (e: CdeError.Unauthenticated) {
        Outcome.Retry("Sign in again to send your changes.")
    } catch (e: CdeError) {
        Outcome.Rejected(e.message ?: "The server rejected that change.")
    }

    /**
     * An annotation created offline has no server id until its create is sent.
     * A later edit to the same annotation carries a placeholder, swapped here
     * for the real id once it is known.
     */
    private fun resolve(annotationId: Long, localId: String): Long =
        if (annotationId > 0) annotationId
        else resolvedIds[localId]
            ?: throw CdeError.Rejected("The item this change refers to was never created.")

    /** Exponential, capped: a phone in a basement should not spin. */
    private fun backoff(attempts: Int): Long =
        minOf(30_000L, 1_000L * (1L shl minOf(attempts, 5)))

    private fun PendingChange.withAttempt(): PendingChange = when (this) {
        is PendingChange.Create -> copy(attempts = attempts + 1)
        is PendingChange.Update -> copy(attempts = attempts + 1)
        is PendingChange.Delete -> copy(attempts = attempts + 1)
        is PendingChange.Resolve -> copy(attempts = attempts + 1)
    }

    /**
     * Annotations for a document, server-first with the cache as the answer
     * when there is no connection — plus anything still queued, so markup made
     * offline is visible immediately instead of disappearing until it syncs.
     */
    suspend fun annotations(documentId: Long): List<CdeAnnotation> {
        val remote = try {
            api.annotations(documentId).also { store.storeAnnotations(documentId, it) }
        } catch (e: CdeError.Offline) {
            store.cachedAnnotations(documentId)
        }
        return remote
    }

    data class RejectedChange(val change: PendingChange, val reason: String)

    private sealed interface Outcome {
        data object Sent : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Retry(val reason: String) : Outcome
    }
}
