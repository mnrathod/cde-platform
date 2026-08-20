import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A document ready to render, with the branch already taken.
public enum OpenedDocument: Sendable {
    /// The URL is nil only when opened with `prefetch: false` and not cached.
    case pdf(ViewerSource.PdfSource, URL?)
    case drawing(ViewerSource.DrawingSource)
    case image(ViewerSource.ImageSource)
    case unsupported(ViewerSource.UnsupportedSource)
}

/// The entry point to the CDE SDK.
///
/// One instance per signed-in user, held for the life of the app:
///
/// ```swift
/// let cde = CdeSDK(configuration: .init(baseURL: URL(string: "https://cde.example.com")!))
/// try await cde.signIn(username: "…", password: "…")
/// let documents = try await cde.documents(projectId: 1)
/// ```
///
/// Everything that touches the network answers from the offline cache when
/// there is none, so a caller does not branch on connectivity — the
/// alternative is every screen in the host app reimplementing that choice,
/// differently.
public actor CdeSDK {

    private let api: CdeAPI
    private let tokens: TokenStore
    private let store: OfflineStore
    private let sync: SyncEngine

    public init(configuration: CdeConfiguration, storageDirectory: URL? = nil) {
        let tokens = TokenStore()
        let store = OfflineStore(directory: storageDirectory)

        self.tokens = tokens
        self.store = store
        self.api = CdeAPI(
            configuration: configuration,
            tokenProvider: { tokens.token },
            onUnauthenticated: { tokens.clear() })
        self.sync = SyncEngine(api: api, store: store)
    }

    public var isSignedIn: Bool { tokens.token != nil }
    public var signedInAs: String? { tokens.username }

    /// Queue depth and progress, for a status indicator in the host app.
    public func observeSyncState(_ handler: @escaping @Sendable (SyncState) -> Void) async {
        await sync.observeState(handler)
    }

    // MARK: - Session

    @discardableResult
    public func signIn(username: String, password: String) async throws -> AuthResult {
        let result = try await api.login(username: username, password: password)
        tokens.store(token: result.token, username: result.username, role: result.role)
        return result
    }

    /// Ends the session.
    ///
    /// Cached documents go; the outbound queue does not, unless
    /// `discardPendingChanges` is set. Markup someone drew on site exists
    /// nowhere else, and signing out — often just to switch account — is not a
    /// statement that they wanted to throw it away.
    public func signOut(discardPendingChanges: Bool = false) {
        tokens.clear()
        store.clearCachedContent()
        if discardPendingChanges { store.replace(with: []) }
    }

    // MARK: - Browsing

    public func projects() async throws -> [Project] {
        try await api.projects()
    }

    /// Documents in a project, falling back to the last list seen offline.
    public func documents(projectId: Int64) async throws -> [CdeDocument] {
        do {
            let documents = try await api.documents(projectId: projectId)
            store.storeDocuments(documents, projectId: projectId)
            return documents
        } catch CdeError.offline {
            return store.cachedDocuments(projectId: projectId)
        }
    }

    // MARK: - Opening a document

    /// How to render a document, and — for a PDF — the file to render from,
    /// downloaded if it is not already cached.
    ///
    /// - Parameter prefetch: when false the PDF is not fetched, so a document
    ///   list can ask cheaply what something is.
    public func open(documentId: Int64, prefetch: Bool = true) async throws -> OpenedDocument {
        let source = try await api.viewerSource(documentId: documentId)

        switch source {
        case .pdf(let pdf):
            // The URL carries ?v=, so it is also the cache key: a new version
            // cannot be served from an old file.
            if let cached = store.cachedFile(for: pdf.path) {
                return .pdf(pdf, cached)
            }
            guard prefetch else { return .pdf(pdf, nil) }
            let data = try await api.pdfData(path: pdf.path)
            return .pdf(pdf, try store.store(data, for: pdf.path))

        case .drawing(let drawing): return .drawing(drawing)
        case .image(let image): return .image(image)
        case .unsupported(let unsupported): return .unsupported(unsupported)
        }
    }

    // MARK: - Annotations

    public func annotations(documentId: Int64) async throws -> [Annotation] {
        try await sync.annotations(documentId: documentId)
    }

    /// Records markup.
    ///
    /// Queued locally first and sent when possible, so the call succeeds with
    /// no signal and the drawing is never lost to a failed request. The
    /// returned annotation carries a negative id until the server assigns a
    /// real one — callers should key on `shapeData.id`, which is stable from
    /// the moment it is drawn.
    @discardableResult
    public func addAnnotation(
        documentId: Int64,
        shape: ShapeData,
        type: AnnotationType = .markup,
        comment: String? = nil
    ) async throws -> Annotation {
        let request = AnnotationRequest(
            documentId: documentId,
            type: type,
            shapeData: try MarkupCodec.encode(shape),
            comment: comment,
            pageNumber: shape.pageNumber)

        store.enqueue(.create(localId: shape.id, request: request, attempts: 0))
        await sync.sync()

        return Annotation(
            id: -1, documentId: documentId, type: type,
            shapeData: request.shapeData, comment: comment,
            pageNumber: shape.pageNumber)
    }

    public func resolveAnnotation(id: Int64) async {
        store.enqueue(.resolve(localId: "resolve-\(id)", annotationId: id, attempts: 0))
        await sync.sync()
    }

    public func deleteAnnotation(id: Int64) async {
        store.enqueue(.delete(localId: "delete-\(id)", annotationId: id, attempts: 0))
        await sync.sync()
    }

    /// Sends anything queued. Call on foreground and when connectivity
    /// returns; it is safe to call more often than necessary.
    ///
    /// - Returns: changes the server refused outright, which will not be
    ///   retried.
    @discardableResult
    public func synchronise() async -> [RejectedChange] {
        await sync.sync()
    }

    // MARK: - Storage

    public func cacheSizeBytes() -> Int64 { store.cacheSizeBytes() }

    public func trimCache(to limit: Int64) { store.trim(to: limit) }
}
