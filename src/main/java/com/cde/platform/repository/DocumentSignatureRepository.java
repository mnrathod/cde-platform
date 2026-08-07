package com.cde.platform.repository;

import com.cde.platform.model.DocumentSignature;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DocumentSignatureRepository extends JpaRepository<DocumentSignature, Long> {
    List<DocumentSignature> findByDocument_IdOrderBySignedAtDesc(Long documentId);

    void deleteByDocument_Id(Long documentId);
    Optional<DocumentSignature> findBySignatureId(String signatureId);
    List<DocumentSignature> findBySigner_UsernameOrderBySignedAtDesc(String username);
}
