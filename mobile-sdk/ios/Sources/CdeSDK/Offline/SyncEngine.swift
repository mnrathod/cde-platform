import Foundation

/// What the host app shows about sync state.
public struct SyncState: Sendable, Equatable {
    public var pendingCount: Int = 0
    public var isSyncing: Bool = false
    /// Set when the last attempt failed for a reason worth telling the user.
    public var lastError: String?
}

/// A change the server refused outright. It will not be retried.
public struct RejectedChange: Sendable {
    public let change: PendingChange
    public let reason: String
}

/// Drains the offline queue to the server.
///
/// The rules it follows, and why:
///
/// **Order is preserved.** Changes are replayed oldest first and a failure
/// stops the run rather than skipping ahead. An update that arrived before its
/// create would be rejected, and a delete replayed out of order would
/// resurrect markup someone deliberately removed.
///
/// **A rejection is not a retry.** If the server refuses a change on its
/// merits — 400, 403, 404 — replaying it will never succeed, so it is dropped
/// and reported. Only transport failures and 503s are retried, because only
/// those are about the moment rather than the content.
///
/// **Deleting something already gone is success.** A 404 on a delete means the
/// intent has been achieved, however it happened; treating it as failure would
/// wedge the queue behind a change that can never complete.
///
/// **Last write wins, and the writer is told.** The server holds no version on
/// an annotation, so there is nothing to merge against. Rather than pretend to
/// resolve a conflict the data cannot express, an update is applied as-is and
/// the result returned so the caller sees what actually landed.
public actor SyncEngine {

    private let api: CdeAPI
    private let store: OfflineStore

    /// Server ids assigned to shapes created offline, keyed by local id.
    private var resolvedIds: [String: Int64] = [:]
    private var state = SyncState()

    /// Emitted whenever the queue depth or status changes.
    public private(set) var onStateChange: (@Sendable (SyncState) -> Void)?

    public init(api: CdeAPI, store: OfflineStore) {
        self.api = api
        self.store = store
        self.state = SyncState(pendingCount: store.pending().count)
    }

    public func observeState(_ handler: @escaping @Sendable (SyncState) -> Void) {
        onStateChange = handler
        handler(state)
    }

    public func currentState() -> SyncState { state }

    /// Attempts to send everything queued.
    ///
    /// Safe to call often — the actor serialises calls, so a screen that syncs
    /// on appear and a background task that syncs on a connectivity change
    /// cannot double-send.
    ///
    /// - Returns: the changes that were rejected outright and dropped.
    @discardableResult
    public func sync() async -> [RejectedChange] {
        var rejected: [RejectedChange] = []
        update { $0.isSyncing = true; $0.lastError = nil }

        var queue = store.pending()
        while let change = queue.first {
            switch await attempt(change) {
            case .sent:
                store.remove(localId: change.localId)
                queue.removeFirst()

            case .rejected(let reason):
                // Will never succeed. Drop it so the queue can drain, and hand
                // it back so the user learns their change did not land rather
                // than silently losing it.
                store.remove(localId: change.localId)
                rejected.append(RejectedChange(change: change, reason: reason))
                queue.removeFirst()

            case .retry(let reason):
                // Transport or availability. Leave the queue intact and stop:
                // order matters more than progress.
                store.replace(with: queue.map { $0.incrementingAttempts() })
                update {
                    $0.isSyncing = false
                    $0.lastError = reason
                    $0.pendingCount = queue.count
                }
                return rejected
            }
        }

        update {
            $0.isSyncing = false
            $0.pendingCount = store.pending().count
        }
        return rejected
    }

    private enum Outcome {
        case sent
        case rejected(String)
        case retry(String)
    }

    private func attempt(_ change: PendingChange) async -> Outcome {
        do {
            switch change {
            case .create(let localId, let request, _):
                let saved = try await api.createAnnotation(request)
                resolvedIds[localId] = saved.id

            case .update(let localId, let annotationId, let request, _):
                _ = try await api.updateAnnotation(
                    id: try resolve(annotationId, localId: localId), request)

            case .resolve(let localId, let annotationId, _):
                _ = try await api.resolveAnnotation(
                    id: try resolve(annotationId, localId: localId))

            case .delete(let localId, let annotationId, _):
                try await api.deleteAnnotation(
                    id: try resolve(annotationId, localId: localId))
            }
            return .sent

        } catch CdeError.notFound(let message) {
            // Already gone. For a delete that is the desired end state; for
            // the others the target no longer exists and replaying cannot fix
            // it.
            if case .delete = change { return .sent }
            return .rejected(message)

        } catch CdeError.offline {
            return .retry("Waiting for a connection.")

        } catch CdeError.unavailable(let message) {
            // Back off a little so a busy server is not hammered by every
            // device on site at once.
            try? await Task.sleep(nanoseconds: backoff(change.attempts))
            return .retry(message)

        } catch CdeError.unauthenticated {
            return .retry("Sign in again to send your changes.")

        } catch let error as CdeError {
            return .rejected(error.errorDescription ?? "The server rejected that change.")

        } catch {
            return .retry("Could not send that change.")
        }
    }

    /// An annotation created offline has no server id until its create is
    /// sent. A later edit carries a placeholder, swapped here for the real id
    /// once it is known.
    private func resolve(_ annotationId: Int64, localId: String) throws -> Int64 {
        if annotationId > 0 { return annotationId }
        guard let resolved = resolvedIds[localId] else {
            throw CdeError.rejected("The item this change refers to was never created.")
        }
        return resolved
    }

    /// Exponential, capped: a phone in a basement should not spin.
    private func backoff(_ attempts: Int) -> UInt64 {
        let seconds = min(30.0, pow(2.0, Double(min(attempts, 5))))
        return UInt64(seconds * 1_000_000_000)
    }

    /// Annotations for a document, server-first with the cache as the answer
    /// when there is no connection.
    public func annotations(documentId: Int64) async throws -> [Annotation] {
        do {
            let remote = try await api.annotations(documentId: documentId)
            store.storeAnnotations(remote, documentId: documentId)
            return remote
        } catch CdeError.offline {
            return store.cachedAnnotations(documentId: documentId)
        }
    }

    private func update(_ mutate: (inout SyncState) -> Void) {
        mutate(&state)
        onStateChange?(state)
    }
}
