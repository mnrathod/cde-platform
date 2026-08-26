import XCTest
@testable import CdeSDK

/// What the server actually sends, pinned.
///
/// The SDK decodes a fixed set of shapes. When one of them changes on the
/// server, nothing here fails at build time and nothing fails at run time
/// until a phone in the field makes the call — which is the whole problem with
/// a hand-written client. These payloads are the shapes recorded in the
/// generated OpenAPI specification, so a server change that breaks the SDK
/// breaks this test first.
///
/// Both cases below are drift that had already happened: the document listing
/// became a page envelope, and errors became RFC 9457 problem responses.
/// Neither was noticed, because the SDK compiles perfectly well against a
/// contract it no longer matches.
///
/// The same vectors are asserted by the Android `WireContractTest`. Both
/// clients decode the same wire, so both are held to the same payloads.
final class WireContractTests: XCTestCase {

    private let decoder = JSONDecoder()

    // MARK: - The document listing is a page, not an array

    /// One page of two documents, as `GET /api/documents/project/{id}` answers.
    private let documentPagePayload = Data("""
    {
      "content": [
        { "id": 1180, "name": "Ground Floor GA", "fileName": "ga-plan.pdf",
          "fileType": "application/pdf", "fileSize": 284913,
          "documentType": "DRAWING", "status": "APPROVED",
          "revision": "P01.02", "drawingNumber": "A-100", "sheetNumber": null,
          "description": null, "projectId": 42, "uploadedBy": "sample.engineer",
          "createdAt": "2026-08-19T00:58:55.793394585",
          "updatedAt": "2026-08-19T00:58:55.793394585" },
        { "id": 1181, "name": "Federated Model", "fileName": "model.ifc",
          "fileType": "application/x-step", "fileSize": 41943040,
          "documentType": "BIM_MODEL", "status": "DRAFT",
          "revision": null, "drawingNumber": null, "sheetNumber": null,
          "description": null, "projectId": 42, "uploadedBy": "sample.engineer",
          "createdAt": null, "updatedAt": null }
      ],
      "number": 0,
      "size": 50,
      "totalElements": 137,
      "totalPages": 3,
      "first": true,
      "last": false
    }
    """.utf8)

    func testADocumentListingDecodesAsAPage() throws {
        let page = try decoder.decode(DocumentPage.self, from: documentPagePayload)

        XCTAssertEqual(page.content.count, 2)
        XCTAssertEqual(page.content[0].name, "Ground Floor GA")
        XCTAssertEqual(page.content[1].projectId, 42)
    }

    func testThePageReportsWhatTheCallerHasNotBeenGivenYet() throws {
        let page = try decoder.decode(DocumentPage.self, from: documentPagePayload)

        // Without these, two documents look like the whole project. A listing
        // that silently stops at the page size is worse than one that fails:
        // the user sees a plausible, incomplete list and has no way to tell.
        XCTAssertEqual(page.totalElements, 137)
        XCTAssertEqual(page.totalPages, 3)
        XCTAssertEqual(page.number, 0)
        XCTAssertTrue(page.first)
        XCTAssertFalse(page.last)
    }

    func testDecodingTheListingAsABareArrayFailsOutright() {
        // This is what the SDK did. It is recorded because the failure mode is
        // the argument for the change: not a wrong answer, an exception on
        // every document list in the product, on every installed copy.
        XCTAssertThrowsError(
            try decoder.decode([CdeDocument].self, from: documentPagePayload))
    }

    // MARK: - Errors are RFC 9457 problem responses

    func testARefusalIsExplainedFromTheProblemDetail() {
        let body = Data("""
        {
          "type": "/problems/upload-rejected",
          "title": "Upload rejected",
          "status": 422,
          "detail": "A single chunk may be at most 8 MB.",
          "instance": "/api/documents/upload/chunk",
          "traceId": "00000000000000000000000000000000"
        }
        """.utf8)

        // Not the title, which is the class of problem rather than this one.
        XCTAssertEqual(problemMessage(in: body), "A single chunk may be at most 8 MB.")
    }

    func testTheTitleStandsInWhenThereIsNoDetail() {
        let body = Data(#"{ "title": "Upload rejected", "status": 422 }"#.utf8)

        XCTAssertEqual(problemMessage(in: body), "Upload rejected")
    }

    func testNothingIsShownWhenTheBodyExplainsNothing() {
        // The old key. Reading it produced nil against every real response, so
        // every refusal reached the user as the SDK's own generic sentence and
        // the server's explanation was discarded unread.
        XCTAssertNil(problemMessage(in: Data(#"{ "message": "…", "status": 500 }"#.utf8)))
        XCTAssertNil(problemMessage(in: Data("<html>502 Bad Gateway</html>".utf8)))
        XCTAssertNil(problemMessage(in: Data()))
    }
}
