#if canImport(CryptoKit)
import CryptoKit
#endif
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A change made on this device that the server has not accepted yet.
///
/// Queued rather than applied optimistically-and-forgotten: a site visit can
/// produce an hour of markup with no signal, and losing it because a request
/// failed once is not recoverable by the person who drew it.
public enum PendingChange: Codable, Sendable {
    case create(localId: String, request: AnnotationRequest, attempts: Int)
    case update(localId: String, annotationId: Int64, request: AnnotationRequest, attempts: Int)
    case delete(localId: String, annotationId: Int64, attempts: Int)
    case resolve(localId: String, annotationId: Int64, attempts: Int)

    public var localId: String {
        switch self {
        case .create(let id, _, _), .update(let id, _, _, _),
             .delete(let id, _, _), .resolve(let id, _, _):
            return id
        }
    }

    /// Attempts so far. Used to back off, and to stop retrying forever.
    public var attempts: Int {
        switch self {
        case .create(_, _, let n), .update(_, _, _, let n),
             .delete(_, _, let n), .resolve(_, _, let n):
            return n
        }
    }

    func incrementingAttempts() -> PendingChange {
        switch self {
        case .create(let id, let request, let n):
            return .create(localId: id, request: request, attempts: n + 1)
        case .update(let id, let annotationId, let request, let n):
            return .update(localId: id, annotationId: annotationId, request: request, attempts: n + 1)
        case .delete(let id, let annotationId, let n):
            return .delete(localId: id, annotationId: annotationId, attempts: n + 1)
        case .resolve(let id, let annotationId, let n):
            return .resolve(localId: id, annotationId: annotationId, attempts: n + 1)
        }
    }
}

/// What a document looks like on disk between sessions.
///
/// Files are keyed by the URL the server gave, which already carries `?v=` —
/// so a new version is a new key and a stale copy can never be served for it.
/// That is why there is no expiry check: freshness is the server's statement,
/// not this cache's guess.
public final class OfflineStore: @unchecked Sendable {

    private let root: URL
    private let files: URL
    private let metadata: URL
    private let queueFile: URL
    private let lock = NSLock()

    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(directory: URL? = nil) {
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cde-sdk", isDirectory: true)

        root = base
        files = base.appendingPathComponent("documents", isDirectory: true)
        metadata = base.appendingPathComponent("metadata", isDirectory: true)
        queueFile = base.appendingPathComponent("pending.json")

        for directory in [root, files, metadata] {
            try? FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true)
        }
        // Cached documents are re-downloadable; backing them up would bloat
        // every iCloud backup with drawings that already live on the server.
        excludeFromBackup(files)
    }

    // MARK: - Document bytes

    public func cachedFile(for key: String) -> URL? {
        let candidate = files.appendingPathComponent(digest(key))
        guard let size = try? candidate.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size > 0
        else { return nil }
        return candidate
    }

    /// Writes to a temporary file and moves it into place, so a download
    /// interrupted by the app being killed leaves no half-file that would
    /// later be opened as a whole document.
    @discardableResult
    public func store(_ data: Data, for key: String) throws -> URL {
        let target = files.appendingPathComponent(digest(key))
        let temporary = files.appendingPathComponent(digest(key) + ".part")
        try data.write(to: temporary, options: .atomic)
        if FileManager.default.fileExists(atPath: target.path) {
            try? FileManager.default.removeItem(at: target)
        }
        try FileManager.default.moveItem(at: temporary, to: target)
        return target
    }

    // MARK: - Metadata

    public func storeDocuments(_ documents: [CdeDocument], projectId: Int64) {
        write(documents, to: metadata.appendingPathComponent("project-\(projectId).json"))
    }

    public func cachedDocuments(projectId: Int64) -> [CdeDocument] {
        read([CdeDocument].self,
             from: metadata.appendingPathComponent("project-\(projectId).json")) ?? []
    }

    public func storeAnnotations(_ annotations: [Annotation], documentId: Int64) {
        write(annotations, to: metadata.appendingPathComponent("annotations-\(documentId).json"))
    }

    public func cachedAnnotations(documentId: Int64) -> [Annotation] {
        read([Annotation].self,
             from: metadata.appendingPathComponent("annotations-\(documentId).json")) ?? []
    }

    // MARK: - The outbound queue

    public func pending() -> [PendingChange] {
        lock.lock(); defer { lock.unlock() }
        return read([PendingChange].self, from: queueFile) ?? []
    }

    public func enqueue(_ change: PendingChange) {
        lock.lock(); defer { lock.unlock() }
        let existing = read([PendingChange].self, from: queueFile) ?? []
        write(existing + [change], to: queueFile)
    }

    public func replace(with changes: [PendingChange]) {
        lock.lock(); defer { lock.unlock() }
        write(changes, to: queueFile)
    }

    public func remove(localId: String) {
        lock.lock(); defer { lock.unlock() }
        let existing = read([PendingChange].self, from: queueFile) ?? []
        write(existing.filter { $0.localId != localId }, to: queueFile)
    }

    // MARK: - Housekeeping

    /// Total bytes held, so the host app can show and bound it.
    public func cacheSizeBytes() -> Int64 {
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: files, includingPropertiesForKeys: [.fileSizeKey])
        else { return 0 }
        return contents.reduce(0) { total, url in
            total + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }

    /// Drops cached document bytes, oldest first, until under `limit`.
    ///
    /// Only the files go. Metadata and the pending queue are never evicted:
    /// they are small, and the queue holds work that exists nowhere else.
    public func trim(to limit: Int64) {
        guard var total = Optional(cacheSizeBytes()), total > limit else { return }
        let keys: Set<URLResourceKey> = [.contentModificationDateKey, .fileSizeKey]
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: files, includingPropertiesForKeys: Array(keys))
        else { return }

        let oldestFirst = contents.sorted { left, right in
            let l = (try? left.resourceValues(forKeys: keys).contentModificationDate) ?? .distantPast
            let r = (try? right.resourceValues(forKeys: keys).contentModificationDate) ?? .distantPast
            return l < r
        }

        for url in oldestFirst {
            guard total > limit else { return }
            let size = Int64((try? url.resourceValues(forKeys: keys).fileSize) ?? 0)
            try? FileManager.default.removeItem(at: url)
            total -= size
        }
    }

    /// Everything except the pending queue, which would lose unsent work.
    public func clearCachedContent() {
        for directory in [files, metadata] {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true)
        }
        excludeFromBackup(files)
    }

    // MARK: - Helpers

    private func write<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? encoder.encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private func read<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    /// A stable, filesystem-safe name for a cache key.
    ///
    /// Must be deterministic across launches — it is how a cached file is
    /// found again on the next run. `Swift.hashValue` is not: its seed is
    /// randomised per process, so using it would silently orphan every cached
    /// file at each launch and quietly turn the cache off.
    private func digest(_ key: String) -> String {
        #if canImport(CryptoKit)
        return SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        #else
        // Non-Apple platforms build only for off-device testing; FNV-1a is
        // deterministic, which is the property that matters here.
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in Data(key.utf8) {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return String(format: "%016lx", hash)
        #endif
    }

    private func excludeFromBackup(_ url: URL) {
        var target = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? target.setResourceValues(values)
    }
}
