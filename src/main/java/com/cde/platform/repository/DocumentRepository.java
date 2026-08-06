package com.cde.platform.repository;

import com.cde.platform.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByProject_Id(Long projectId);
    Page<Document> findByProject_Id(Long projectId, Pageable pageable);
    List<Document> findByProject_IdAndDocumentType(Long projectId, Document.DocumentType type);
    List<Document> findByProject_IdAndStatus(Long projectId, Document.DocumentStatus status);
}
