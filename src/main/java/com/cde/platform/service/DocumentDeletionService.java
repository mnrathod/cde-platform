package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import com.cde.platform.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Deletes documents and projects together with everything that references
 * them.
 *
 * Annotations, replies and signatures all hold a non-null foreign key to the
 * document, and none of the mappings cascade, so deleting a document
 * directly raises a referential-integrity error. Worse, that error surfaced
 * to the client as 403 — Spring Security's ExceptionTranslationFilter turns
 * an unhandled exception into an access-denied response — which made a data
 * problem look like a permissions problem.
 *
 * Ordering is the whole point of this class: dependents must go before the
 * rows they point at, and both controllers need the same order, so it lives
 * here rather than being duplicated in each.
 */
@Service
public class DocumentDeletionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeletionService.class);

    private final DocumentRepository          documentRepo;
    private final AnnotationRepository        annotationRepo;
    private final AnnotationReplyRepository   replyRepo;
    private final DocumentSignatureRepository signatureRepo;
    private final ProjectRepository           projectRepo;

    public DocumentDeletionService(DocumentRepository documentRepo,
                                   AnnotationRepository annotationRepo,
                                   AnnotationReplyRepository replyRepo,
                                   DocumentSignatureRepository signatureRepo,
                                   ProjectRepository projectRepo) {
        this.documentRepo  = documentRepo;
        this.annotationRepo = annotationRepo;
        this.replyRepo      = replyRepo;
        this.signatureRepo  = signatureRepo;
        this.projectRepo    = projectRepo;
    }

    /**
     * Deletes a document and its annotations, annotation replies and
     * signatures.
     *
     * @return false if no such document exists
     */
    @Transactional
    public boolean deleteDocument(Long documentId) {
        if (!documentRepo.existsById(documentId)) return false;
        deleteDocumentDependents(documentId);
        documentRepo.deleteById(documentId);
        log.info("Deleted document {} and its dependents", documentId);
        return true;
    }

    /**
     * Deletes a project along with every document it contains and each of
     * those documents' dependents.
     *
     * @return false if no such project exists
     */
    @Transactional
    public boolean deleteProject(Long projectId) {
        if (!projectRepo.existsById(projectId)) return false;

        List<Long> documentIds = documentRepo.findByProject_Id(projectId)
            .stream().map(document -> document.getId()).toList();

        for (Long documentId : documentIds) {
            deleteDocumentDependents(documentId);
            documentRepo.deleteById(documentId);
        }
        projectRepo.deleteById(projectId);

        log.info("Deleted project {} with {} document(s)", projectId, documentIds.size());
        return true;
    }

    private void deleteDocumentDependents(Long documentId) {
        List<Long> annotationIds = annotationRepo.findByDocument_Id(documentId)
            .stream().map(Annotation::getId).toList();

        // Replies point at annotations, so they have to go first.
        if (!annotationIds.isEmpty()) {
            replyRepo.deleteByAnnotation_IdIn(annotationIds);
        }
        annotationRepo.deleteByDocument_Id(documentId);
        signatureRepo.deleteByDocument_Id(documentId);
    }
}
