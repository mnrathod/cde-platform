package com.cde.sdk.net

import com.cde.sdk.model.CdeDocument
import com.cde.sdk.model.DocumentPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the server actually sends, pinned.
 *
 * <p>The SDK decodes a fixed set of shapes. When one of them changes on the
 * server, nothing here fails at build time and nothing fails at run time until
 * a phone in the field makes the call — which is the whole problem with a
 * hand-written client. These payloads are the shapes recorded in the generated
 * OpenAPI specification, so a server change that breaks the SDK breaks this
 * test first.
 *
 * <p>Both cases below are drift that had already happened: the document
 * listing became a page envelope, and errors became RFC 9457 problem
 * responses. Neither was noticed, because the SDK compiles perfectly well
 * against a contract it no longer matches.
 */
class WireContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ── The document listing is a page, not an array ─────────────────────────

    /** One page of two documents, as `GET /api/documents/project/{id}` answers. */
    private val documentPagePayload = """
        {
          "content": [
            { "id": 1180, "name": "Ground Floor GA", "fileName": "ga-plan.pdf",
              "fileType": "application/pdf", "fileSize": 284913,
              "documentType": "DRAWING", "status": "APPROVED",
              "revision": "P01.02", "drawingNumber": "A-100",
              "projectId": 42, "uploadedBy": "sample.engineer",
              "createdAt": "2026-08-19T00:58:55.793394585",
              "updatedAt": "2026-08-19T00:58:55.793394585" },
            { "id": 1181, "name": "Federated Model", "fileName": "model.ifc",
              "fileType": "application/x-step", "fileSize": 41943040,
              "documentType": "BIM_MODEL", "status": "DRAFT",
              "projectId": 42, "uploadedBy": "sample.engineer" }
          ],
          "number": 0,
          "size": 50,
          "totalElements": 137,
          "totalPages": 3,
          "first": true,
          "last": false
        }
    """.trimIndent()

    @Test
    fun `a document listing decodes as a page`() {
        val page = json.decodeFromString<DocumentPage>(documentPagePayload)

        assertEquals(2, page.content.size)
        assertEquals("Ground Floor GA", page.content[0].name)
        assertEquals(42L, page.content[1].projectId)
    }

    @Test
    fun `the page reports what the caller has not been given yet`() {
        val page = json.decodeFromString<DocumentPage>(documentPagePayload)

        // Without these, two documents look like the whole project. A listing
        // that silently stops at the page size is worse than one that fails:
        // the user sees a plausible, incomplete list and has no way to tell.
        assertEquals(137L, page.totalElements)
        assertEquals(3, page.totalPages)
        assertEquals(0, page.number)
        assertTrue(page.first)
        assertTrue(!page.last)
    }

    @Test
    fun `decoding the listing as a bare array fails outright`() {
        // This is what the SDK did. It is recorded because the failure mode is
        // the argument for the change: not a wrong answer, an exception on
        // every document list in the product, on every installed copy.
        assertThrows(Exception::class.java) {
            json.decodeFromString<List<CdeDocument>>(documentPagePayload)
        }
    }

    // ── Errors are RFC 9457 problem responses ────────────────────────────────

    @Test
    fun `a refusal is explained from the problem detail`() {
        val body = """
            {
              "type": "/problems/upload-rejected",
              "title": "Upload rejected",
              "status": 422,
              "detail": "A single chunk may be at most 8 MB.",
              "instance": "/api/documents/upload/chunk",
              "traceId": "00000000000000000000000000000000"
            }
        """.trimIndent()

        // Not the title, which is the class of problem rather than this one.
        assertEquals("A single chunk may be at most 8 MB.", problemMessage(json, body))
    }

    @Test
    fun `the title stands in when there is no detail`() {
        val body = """{ "title": "Upload rejected", "status": 422 }"""

        assertEquals("Upload rejected", problemMessage(json, body))
    }

    @Test
    fun `nothing is shown when the body explains nothing`() {
        // The old key. Reading it produced null against every real response,
        // so every refusal reached the user as the SDK's own generic sentence
        // and the server's explanation was discarded unread.
        assertNull(problemMessage(json, """{ "message": "…" , "status": 500 }"""))
        assertNull(problemMessage(json, "<html>502 Bad Gateway</html>"))
        assertNull(problemMessage(json, ""))
    }
}
