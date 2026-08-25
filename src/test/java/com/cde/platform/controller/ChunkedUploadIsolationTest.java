package com.cde.platform.controller;

import com.cde.platform.model.Project;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.tenancy.TenantContextBinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A chunked upload belongs to one tenant, and nobody else can reach into it.
 *
 * <p>The identifier tying chunks together is chosen by the client, and the
 * chunks were held in one process-wide map keyed by that identifier and
 * nothing else. Two tenants using the same string shared a store, which gave
 * an attacker who guessed one — or simply collided with one — two distinct
 * powers, both asserted below: contaminating somebody else's file with their
 * own bytes, and stopping somebody else's upload from ever completing.
 *
 * <p>None of this goes near the database, so Row-Level Security never saw it.
 * That is worth stating plainly: the isolation suite was green throughout,
 * because it tests the store that was protected rather than the one that was
 * not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = ChunkedUploadIsolationTest.USERNAME)
class ChunkedUploadIsolationTest {

    static final String USERNAME = "chunk-tenant-a";

    private static final String OWN_FIRST  = "AAAA-first-chunk-from-the-caller";
    private static final String OWN_SECOND = "BBBB-second-chunk-from-the-caller";
    private static final String FOREIGN    = "ZZZZ-belongs-to-another-tenant";

    @Autowired MockMvc            mockMvc;
    @Autowired TenantRepository   tenantRepository;
    @Autowired ProjectRepository  projectRepository;
    @Autowired UserRepository     userRepository;
    @Autowired DocumentRepository documentRepository;

    private Long ownProjectId;
    private long foreignTenantId;

    @BeforeEach
    void seedBothTenants() {
        User caller = userRepository.findByUsername(USERNAME).orElseGet(() ->
            userRepository.save(User.builder()
                .username(USERNAME).email(USERNAME + "@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build()));

        ownProjectId = projectRepository.save(
            Project.builder().name("Chunks " + System.nanoTime()).owner(caller).build()).getId();

        foreignTenantId = tenantRepository.save(Tenant.builder()
            .slug("chunk-foreign-" + System.nanoTime())
            .name("Another Appointed Party")
            .build()).getId();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MockMultipartFile chunk(String content) {
        return new MockMultipartFile("chunk", "part.bin",
            "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends one chunk as the caller. Supplying a project offers to complete. */
    private ResultActions send(String uploadId, int index, int total,
                               String content, boolean completing) throws Exception {
        var request = multipart("/api/documents/upload/chunk")
            .file(chunk(content))
            .param("uploadId", uploadId)
            .param("chunkIndex", String.valueOf(index))
            .param("totalChunks", String.valueOf(total))
            .param("fileName", "mine.bin");

        if (completing) {
            request = request.param("projectId", String.valueOf(ownProjectId));
        }
        return mockMvc.perform(request);
    }

    /** Stages a chunk as a user of the other tenant, under the given id. */
    private void sendAsForeignTenant(String uploadId, int index, int total) throws Exception {
        Optional<Long> caller = TenantContext.currentTenantId();
        TenantContextBinder.bind(foreignTenantId);
        try {
            mockMvc.perform(multipart("/api/documents/upload/chunk")
                    .file(chunk(FOREIGN))
                    .param("uploadId", uploadId)
                    .param("chunkIndex", String.valueOf(index))
                    .param("totalChunks", String.valueOf(total))
                    .param("fileName", "theirs.bin"))
                .andExpect(status().isOk());
        } finally {
            TenantContextBinder.restore(caller);
        }
    }

    private String contentOfDocumentIn(String responseBody) throws Exception {
        int idStart = responseBody.indexOf("\"id\":") + 5;
        long id = Long.parseLong(
            responseBody.substring(idStart, responseBody.indexOf(',', idStart)).trim());

        Path stored = Path.of(documentRepository.findById(id)
            .orElseThrow(() -> new IllegalStateException(
                "The API reported creating document " + id + ", which is not there."))
            .getFilePath());

        return Files.readString(stored, StandardCharsets.UTF_8);
    }

    // ── The two powers a shared store handed out ─────────────────────────────

    @Test
    @DisplayName("another tenant's chunk cannot complete, or contaminate, this upload")
    void aForeignChunkNeitherCompletesNorContaminates() throws Exception {
        String contested = "0d4c1f8e-2b7a-4c31-9de6-5a0b83f27c14";

        // The other tenant occupies the last index of a three-chunk upload and
        // never finishes. The caller sends only its own first two.
        sendAsForeignTenant(contested, 2, 3);

        send(contested, 0, 3, OWN_FIRST, false).andExpect(status().isOk());

        // Two of three have arrived, so this is progress. Sharing a store made
        // it a completion, and the file it produced ended with the other
        // tenant's bytes.
        send(contested, 1, 3, OWN_SECOND, true).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the assembled file holds this tenant's bytes and nothing else")
    void aForeignChunkIsNeverAssembledIn() throws Exception {
        String contested = "9f1b3c5d-7e2a-4b60-8c14-2d7e6f0a9b3c";

        // A perfectly valid chunk — index 1 of 2 — that simply belongs to
        // somebody else. Sharing a store, the caller's very first chunk then
        // brought the count to two and completed the upload, and the file it
        // produced was the caller's first half followed by a stranger's
        // second.
        sendAsForeignTenant(contested, 1, 2);

        send(contested, 0, 2, OWN_FIRST, true).andExpect(status().isOk());

        String created = send(contested, 1, 2, OWN_SECOND, true)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(contentOfDocumentIn(created))
            .as("the assembled file must hold this tenant's chunks, in order, and nothing else")
            .isEqualTo(OWN_FIRST + OWN_SECOND);
    }

    @Test
    @DisplayName("an ordinary chunked upload still assembles correctly")
    void chunksAssembleInOrder() throws Exception {
        // The positive control. Without it, a fix that broke chunked upload
        // outright would satisfy both assertions above.
        String uploadId = "5c7e2a91-3d48-4f6b-b0e2-7a1c9d4f8e30";

        send(uploadId, 1, 2, OWN_SECOND, false).andExpect(status().isOk());

        String created = send(uploadId, 0, 2, OWN_FIRST, true)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        // Sent out of order deliberately: chunks may arrive in any order and
        // the file has to be assembled by index, not by arrival.
        assertThat(contentOfDocumentIn(created)).isEqualTo(OWN_FIRST + OWN_SECOND);
    }
}
