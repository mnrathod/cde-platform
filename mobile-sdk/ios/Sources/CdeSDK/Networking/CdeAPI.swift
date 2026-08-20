import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Everything that can go wrong talking to the CDE, as one type.
///
/// Callers should not switch on HTTP status codes — the mapping from status to
/// meaning belongs here, once, so a caller handles `.unauthenticated` rather
/// than remembering what a 401 means on a refreshed token.
public enum CdeError: LocalizedError, Sendable {
    /// No usable token. The SDK has already cleared it; sign in again.
    case unauthenticated
    /// Authenticated, but this account may not do it.
    case forbidden(String)
    case notFound(String)
    /// The server rejected the request and said why. Safe to show.
    case rejected(String)
    /// Reachable but temporarily unable — the converter being down, most
    /// often. Distinct from `.offline` because retrying is worth it but
    /// caching is not the answer.
    case unavailable(String)
    /// No usable network. The offline cache should answer instead.
    case offline
    case unexpected(String)

    public var errorDescription: String? {
        switch self {
        case .unauthenticated: return "Sign in again to continue."
        case .forbidden(let message): return message
        case .notFound(let message): return message
        case .rejected(let message): return message
        case .unavailable(let message): return message
        case .offline: return "No connection."
        case .unexpected(let message): return message
        }
    }
}

/// Where the SDK talks to, and how patiently.
public struct CdeConfiguration: Sendable {
    public let baseURL: URL
    public let timeout: TimeInterval

    public init(baseURL: URL, timeout: TimeInterval = 30) {
        self.baseURL = baseURL
        self.timeout = timeout
    }
}

public struct AuthResult: Codable, Sendable {
    public let token: String
    public let username: String
    public let role: String
}

/// The HTTP surface of the CDE.
///
/// Decoding is lenient about unknown keys by construction — Swift's
/// `JSONDecoder` ignores them — which is what stops a field added to the
/// server from breaking every installed copy of the app in the field.
public actor CdeAPI {

    private let configuration: CdeConfiguration
    private let session: URLSession
    private let tokenProvider: @Sendable () -> String?
    private let onUnauthenticated: @Sendable () -> Void

    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    public init(
        configuration: CdeConfiguration,
        tokenProvider: @escaping @Sendable () -> String?,
        onUnauthenticated: @escaping @Sendable () -> Void
    ) {
        self.configuration = configuration
        self.tokenProvider = tokenProvider
        self.onUnauthenticated = onUnauthenticated

        let sessionConfiguration = URLSessionConfiguration.default
        sessionConfiguration.timeoutIntervalForRequest = configuration.timeout
        // The SDK caches deliberately and by version; letting URLSession also
        // cache would produce two policies that disagree about staleness.
        sessionConfiguration.requestCachePolicy = .reloadIgnoringLocalCacheData
        #if canImport(Darwin)
        // Fail fast when there is no route, so the caller falls through to the
        // cache instead of hanging. The property is read-only in
        // swift-corelibs-foundation, which only affects off-device test runs.
        sessionConfiguration.waitsForConnectivity = false
        #endif
        self.session = URLSession(configuration: sessionConfiguration)
    }

    // MARK: - Authentication

    public func login(username: String, password: String) async throws -> AuthResult {
        struct Body: Encodable { let username: String; let password: String }
        let data = try await send(
            "POST", path: "/api/auth/login",
            body: try encoder.encode(Body(username: username, password: password)),
            authenticated: false)
        return try decoder.decode(AuthResult.self, from: data)
    }

    // MARK: - Projects and documents

    public func projects() async throws -> [Project] {
        try decoder.decode([Project].self, from: await get("/api/projects"))
    }

    public func documents(projectId: Int64) async throws -> [CdeDocument] {
        try decoder.decode([CdeDocument].self,
                           from: await get("/api/documents/project/\(projectId)"))
    }

    public func document(id: Int64) async throws -> CdeDocument {
        try decoder.decode(CdeDocument.self, from: await get("/api/documents/\(id)"))
    }

    /// How to open a document.
    ///
    /// The response is polymorphic on `type`, so it is decoded by hand into
    /// the `ViewerSource` enum rather than into a struct of optionals — the
    /// branch is taken once, here, instead of at every use.
    public func viewerSource(documentId: Int64) async throws -> ViewerSource {
        let data = try await get("/api/viewer/\(documentId)")
        guard let payload = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw CdeError.unexpected("The server sent a document description we could not read.") }

        func string(_ key: String) -> String? {
            guard let value = payload[key] as? String, !value.isEmpty else { return nil }
            return value
        }

        let name = string("name") ?? "Untitled"
        let revision = string("revision")
        let drawingNumber = string("drawingNumber")

        switch string("type") {
        case "pdf":
            return .pdf(.init(
                name: name, revision: revision, drawingNumber: drawingNumber,
                fileName: string("fileName"),
                path: string("pdfUrl") ?? "/api/viewer/\(documentId)/pdf",
                version: payload["version"] as? Int ?? 1))

        case "svg":
            return .drawing(.init(
                name: name, revision: revision, drawingNumber: drawingNumber,
                svg: string("content") ?? ""))

        case "image":
            return .image(.init(
                name: name, revision: revision, drawingNumber: drawingNumber,
                path: string("imageUrl") ?? "/api/viewer/\(documentId)/pdf"))

        case let other:
            return .unsupported(.init(
                name: name, revision: revision, drawingNumber: drawingNumber,
                type: other ?? "unknown", fileName: string("fileName")))
        }
    }

    /// Raw PDF bytes.
    public func pdfData(path: String) async throws -> Data {
        try await get(path)
    }

    // MARK: - Annotations

    public func annotations(documentId: Int64) async throws -> [Annotation] {
        try decoder.decode([Annotation].self,
                           from: await get("/api/annotations/document/\(documentId)"))
    }

    public func createAnnotation(_ request: AnnotationRequest) async throws -> Annotation {
        let data = try await send("POST", path: "/api/annotations",
                                  body: try encoder.encode(request))
        return try decoder.decode(Annotation.self, from: data)
    }

    public func updateAnnotation(id: Int64, _ request: AnnotationRequest) async throws -> Annotation {
        let data = try await send("PUT", path: "/api/annotations/\(id)",
                                  body: try encoder.encode(request))
        return try decoder.decode(Annotation.self, from: data)
    }

    @discardableResult
    public func resolveAnnotation(id: Int64) async throws -> Annotation {
        let data = try await send("PATCH", path: "/api/annotations/\(id)/resolve",
                                  body: Data("{}".utf8))
        return try decoder.decode(Annotation.self, from: data)
    }

    public func deleteAnnotation(id: Int64) async throws {
        _ = try await send("DELETE", path: "/api/annotations/\(id)", body: nil)
    }

    // MARK: - Plumbing

    private func url(for path: String) -> URL? {
        if path.hasPrefix("http") { return URL(string: path) }
        return URL(string: configuration.baseURL.absoluteString.trimmingTrailingSlash() + path)
    }

    private func get(_ path: String) async throws -> Data {
        try await send("GET", path: path, body: nil)
    }

    private func send(
        _ method: String, path: String, body: Data?, authenticated: Bool = true
    ) async throws -> Data {
        guard let url = url(for: path) else {
            throw CdeError.unexpected("That address is not valid.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if authenticated, let token = tokenProvider() {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // No route to the server at all. Distinguished from a server that
            // answered badly, because the offline cache can serve this case
            // and cannot serve the other.
            throw CdeError.offline
        }

        guard let http = response as? HTTPURLResponse else {
            throw CdeError.unexpected("The server sent a response we could not read.")
        }
        guard !(200..<300).contains(http.statusCode) else { return data }

        let message = serverMessage(in: data)
        switch http.statusCode {
        case 401:
            onUnauthenticated()
            throw CdeError.unauthenticated
        case 403:
            throw CdeError.forbidden(message ?? "You do not have permission to do that.")
        case 404:
            throw CdeError.notFound(message ?? "That document is no longer available.")
        case 400, 409, 422:
            throw CdeError.rejected(message ?? "The server rejected that request.")
        case 503:
            throw CdeError.unavailable(message ?? "The service is busy. Try again shortly.")
        default:
            throw CdeError.unexpected(message ?? "Something went wrong (\(http.statusCode)).")
        }
    }

    /// The server writes `{ "message": ... }` for text meant to be read by a
    /// person. Anything else is discarded rather than shown: a stack trace or
    /// an internal identifier in front of a user is both unhelpful and a
    /// disclosure.
    private func serverMessage(in data: Data) -> String? {
        guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return payload["message"] as? String
    }
}

private extension String {
    func trimmingTrailingSlash() -> String {
        hasSuffix("/") ? String(dropLast()) : self
    }
}
