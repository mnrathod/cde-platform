package com.cde.platform.service;

import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.model.DocumentVersion.DocumentOperation;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.FormFieldBuilder.FieldPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Places and removes form fields on a document, committing each change as a
 * new version.
 *
 * <p>Wraps {@link FormFieldBuilder} — which knows about PDFs and nothing about
 * this application — with the document lookup, version commit and error
 * translation the rest of the system expects, exactly as the processing and
 * page services do.
 */
@Service
public class FormDesignService {

    private static final Logger log = LoggerFactory.getLogger(FormDesignService.class);

    private final FormFieldBuilder       builder;
    private final DocumentVersionService versionService;
    private final DocumentRepository     documentRepo;
    private final UserRepository         userRepo;

    public FormDesignService(FormFieldBuilder builder,
                             DocumentVersionService versionService,
                             DocumentRepository documentRepo,
                             UserRepository userRepo) {
        this.builder        = builder;
        this.versionService = versionService;
        this.documentRepo   = documentRepo;
        this.userRepo       = userRepo;
    }

    /** A committed change to a document's form. */
    public record FormChange(DocumentVersion version, List<String> fields) {}

    @Transactional
    public FormChange addFields(Long documentId, List<FieldPlacement> placements, String username) {
        if (placements == null || placements.isEmpty()) {
            throw new DocumentProcessingException("Place at least one field.");
        }
        return apply(documentId, username,
            (source, output) -> builder.addFields(source, output, placements),
            names -> "Added %d form field(s): %s".formatted(names.size(), String.join(", ", names)));
    }

    @Transactional
    public FormChange removeFields(Long documentId, List<String> names, String username) {
        if (names == null || names.isEmpty()) {
            throw new DocumentProcessingException("Name at least one field to remove.");
        }
        return apply(documentId, username,
            (source, output) -> builder.removeFields(source, output, names),
            removed -> removed.isEmpty()
                ? "No matching form fields to remove"
                : "Removed %d form field(s): %s".formatted(removed.size(), String.join(", ", removed)));
    }

    /** Rewrites the document's form and commits the result. */
    private FormChange apply(Long documentId, String username,
                             FormOperation operation, SummaryOf summary) {
        Document document = requireDocumentWithFile(documentId);
        Path output = null;
        try {
            output = versionService.allocateWorkPath(document, "form");
            List<String> affected = operation.run(Paths.get(document.getFilePath()), output);

            DocumentVersion version = versionService.commit(document, output,
                DocumentOperation.FORM_DESIGN, summary.of(affected), resolveActor(username));
            output = null;   // ownership passed to the version chain

            log.info("Document {} form updated: {}", documentId, summary.of(affected));
            return new FormChange(version, affected);

        } catch (IllegalArgumentException e) {
            // The builder's messages name the offending field, which is more
            // use to whoever placed it than a generic rejection.
            throw new DocumentProcessingException(e.getMessage(), e);
        } catch (IOException e) {
            throw new DocumentProcessingException("The form could not be updated.", e);
        } finally {
            discard(output);
        }
    }

    @FunctionalInterface
    private interface FormOperation {
        List<String> run(Path source, Path output) throws IOException;
    }

    @FunctionalInterface
    private interface SummaryOf {
        String of(List<String> fields);
    }

    private Document requireDocumentWithFile(Long documentId) {
        Document document = documentRepo.findById(documentId)
            .orElseThrow(() -> new DocumentProcessingException("Document not found."));
        if (document.getFilePath() == null || document.getFilePath().isBlank()) {
            throw new DocumentProcessingException("This document has no stored file.");
        }
        return document;
    }

    private User resolveActor(String username) {
        return username == null ? null : userRepo.findByUsername(username).orElse(null);
    }

    private void discard(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", path, e.getMessage());
        }
    }
}
