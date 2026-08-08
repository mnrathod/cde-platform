package com.cde.platform.service;

import com.cde.platform.collaboration.CollaborationBroadcaster;
import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.model.DocumentVersion.DocumentOperation;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.DocumentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains the version chain behind a document's bytes.
 *
 * <p>Redaction, OCR, flattening and form-filling each read the document's
 * current file and write a new one. Before versioning existed each of them
 * streamed that new file straight to the browser and threw it away, so the
 * operations could not be combined — OCR'ing a scan and then redacting it gave
 * you two unrelated downloads instead of one document carrying both changes.
 *
 * <p>Committing a version instead advances {@link Document#getFilePath()} to
 * the new file, which is all that is needed to make the operations compose:
 * every processing endpoint and the viewer already read that field, so the
 * next step in a chain automatically starts from the previous step's output.
 *
 * <p>History is append-only. Nothing here overwrites or deletes an existing
 * version's bytes, and restoring an earlier version copies it forward as a new
 * version rather than truncating the chain — otherwise a signature taken
 * against a discarded version would have nothing left to verify against.
 */
@Service
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);

    /** Subdirectory holding converter output that has not been committed yet. */
    private static final String WORK_DIR = ".work";

    private final DocumentVersionRepository  versionRepo;
    private final DocumentRepository         documentRepo;
    private final CollaborationBroadcaster   broadcaster;

    public DocumentVersionService(DocumentVersionRepository versionRepo,
                                  DocumentRepository documentRepo,
                                  CollaborationBroadcaster broadcaster) {
        this.versionRepo  = versionRepo;
        this.documentRepo = documentRepo;
        this.broadcaster  = broadcaster;
    }

    // ── Queries ──────────────────────────────────────────────────

    /** Full history, newest first. */
    public List<DocumentVersion> listVersions(Long documentId) {
        return versionRepo.findByDocument_IdOrderByVersionNumberDesc(documentId);
    }

    public Optional<DocumentVersion> findVersion(Long documentId, int versionNumber) {
        return versionRepo.findByDocument_IdAndVersionNumber(documentId, versionNumber);
    }

    /** The version {@link Document#getFilePath()} currently points at. */
    public Optional<DocumentVersion> findHead(Long documentId) {
        return versionRepo.findTopByDocument_IdOrderByVersionNumberDesc(documentId);
    }

    // ── Committing ───────────────────────────────────────────────

    /**
     * Reserves a path for a processing step to write its output to.
     *
     * <p>The converter runs as a separate process and needs somewhere to write
     * before we know whether it succeeded, so output lands in a work directory
     * first and is only moved into the version chain by
     * {@link #commit}. The name is unique per call, which matters because
     * repeating an operation would otherwise overwrite the file an earlier
     * version still points at.
     *
     * @param label short operation tag, used only to make the file recognisable
     *              while it exists
     */
    public Path allocateWorkPath(Document document, String label) throws IOException {
        Path work = originalDirectory(document).resolve(WORK_DIR);
        Files.createDirectories(work);
        return work.resolve(label + "_" + UUID.randomUUID() + ".pdf");
    }

    /**
     * Adds a processing result to the document's history and makes it current.
     *
     * <p>The produced file is moved (not copied) out of the work directory
     * into a stable per-version path, so an abandoned run leaves nothing
     * behind in the chain.
     *
     * @param document  the document being processed
     * @param produced  file written by the processing step, from
     *                  {@link #allocateWorkPath}
     * @param operation what produced it
     * @param summary   human-readable note for the history panel
     * @param actor     user who ran the operation; may be null for system runs
     * @return the newly created version
     * @throws IOException if the produced file is missing or cannot be moved
     */
    @Transactional
    public DocumentVersion commit(Document document,
                                  Path produced,
                                  DocumentOperation operation,
                                  String summary,
                                  User actor) throws IOException {

        if (!Files.exists(produced)) {
            throw new IOException("Processing produced no file at " + produced);
        }

        currentVersion(document, actor);

        int versionNumber = nextVersionNumber(document.getId());
        Path target = versionPath(document, versionNumber);
        Files.move(produced, target, StandardCopyOption.REPLACE_EXISTING);

        DocumentVersion version = persistVersion(
            document, versionNumber, target, operation, summary, actor);

        advanceHead(document, version);

        log.info("Document {} committed version {} ({}): {}",
                 document.getId(), versionNumber, operation, summary);
        return version;
    }

    /**
     * Copies an earlier version forward as a new head.
     *
     * <p>Deliberately additive: rolling back by deleting later versions would
     * strand the signatures and history entries that reference them.
     *
     * @return the new version, or empty if the requested version does not exist
     */
    @Transactional
    public Optional<DocumentVersion> restore(Document document, int versionNumber, User actor)
        throws IOException {

        Optional<DocumentVersion> sourceOpt = findVersion(document.getId(), versionNumber);
        if (sourceOpt.isEmpty()) return Optional.empty();
        DocumentVersion source = sourceOpt.get();

        Path sourcePath = Paths.get(source.getFilePath());
        if (!Files.exists(sourcePath)) {
            throw new IOException("Version " + versionNumber + " is missing from storage");
        }

        int newNumber = nextVersionNumber(document.getId());
        Path target   = versionPath(document, newNumber);
        Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);

        DocumentVersion restored = persistVersion(
            document, newNumber, target, DocumentOperation.RESTORE,
            "Restored from version " + versionNumber, actor);

        advanceHead(document, restored);

        log.info("Document {} restored version {} as version {}",
                 document.getId(), versionNumber, newNumber);
        return Optional.of(restored);
    }

    /**
     * Returns the head version, recording the uploaded file as version 1 first
     * if the document has no history yet.
     *
     * <p>Documents uploaded before versioning existed have no rows, and their
     * first processing run would otherwise produce a version 1 describing the
     * processed file while the original went unrecorded — leaving nothing to
     * roll back to and nothing for an existing signature to verify against.
     *
     * @param actor fallback author for the backfilled version when the
     *              document does not record who uploaded it
     */
    @Transactional
    public DocumentVersion currentVersion(Document document, User actor) {
        Optional<DocumentVersion> existing = findHead(document.getId());
        if (existing.isPresent()) return existing.get();

        Path original = Paths.get(document.getFilePath());
        DocumentVersion first = persistVersion(
            document, 1, original, DocumentOperation.UPLOAD, "Original upload",
            document.getUploadedBy() != null ? document.getUploadedBy() : actor);

        document.setCurrentVersion(1);
        documentRepo.save(document);
        return first;
    }

    // ── Internals ────────────────────────────────────────────────

    private DocumentVersion persistVersion(Document document,
                                           int versionNumber,
                                           Path path,
                                           DocumentOperation operation,
                                           String summary,
                                           User actor) {
        return versionRepo.save(DocumentVersion.builder()
            .document(document)
            .versionNumber(versionNumber)
            .fileName(document.getFileName())
            .filePath(path.toAbsolutePath().toString())
            .fileSize(sizeOf(path))
            .operation(operation)
            .summary(summary)
            .contentHash(hashOf(path))
            .createdBy(actor)
            .build());
    }

    /** Points the document at a version's bytes — the step that makes chaining work. */
    private void advanceHead(Document document, DocumentVersion version) {
        document.setFilePath(version.getFilePath());
        document.setFileSize(version.getFileSize());
        document.setCurrentVersion(version.getVersionNumber());
        documentRepo.save(document);

        // Anyone else viewing this document is now looking at bytes that no
        // longer exist. Announcing it here rather than at each call site
        // covers every operation that moves the head, including ones added
        // later.
        broadcaster.versionCommitted(
            document.getId(),
            version.getCreatedBy() != null ? version.getCreatedBy().getUsername() : null,
            version.getVersionNumber(),
            version.getSummary());
    }

    private int nextVersionNumber(Long documentId) {
        return findHead(documentId).map(v -> v.getVersionNumber() + 1).orElse(1);
    }

    /** {@code <upload dir>/<original name>__v<n>.pdf}, alongside the original. */
    private Path versionPath(Document document, int versionNumber) {
        Path original = Paths.get(document.getFilePath()).toAbsolutePath();
        String base   = original.getFileName().toString().replaceAll("\\.[^.]+$", "");
        return originalDirectory(document).resolve(base + "__v" + versionNumber + ".pdf");
    }

    private Path originalDirectory(Document document) {
        Path original = Paths.get(document.getFilePath()).toAbsolutePath();
        Path parent   = original.getParent();
        if (parent == null) throw new IllegalStateException(
            "Document " + document.getId() + " has no storage directory");
        return parent;
    }

    private Long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("Could not size {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * SHA-256 of the file, hex encoded. Stored per version so signature
     * verification can check the bytes that were signed rather than whatever
     * the document happens to point at now.
     */
    private String hashOf(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("Could not hash {}: {}", path, e.getMessage());
            return null;
        }
    }
}
