package com.cde.platform.repository;

import com.cde.platform.model.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    /** Newest first — the order the history panel renders. */
    List<DocumentVersion> findByDocument_IdOrderByVersionNumberDesc(Long documentId);

    Optional<DocumentVersion> findByDocument_IdAndVersionNumber(Long documentId, Integer versionNumber);

    /** The current head, i.e. the version {@code Document.filePath} points at. */
    Optional<DocumentVersion> findTopByDocument_IdOrderByVersionNumberDesc(Long documentId);

    boolean existsByDocument_Id(Long documentId);

    void deleteByDocument_Id(Long documentId);
}
