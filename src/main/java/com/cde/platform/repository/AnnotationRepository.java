package com.cde.platform.repository;

import com.cde.platform.model.Annotation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnnotationRepository extends JpaRepository<Annotation, Long> {
    List<Annotation> findByDocument_Id(Long documentId);

    void deleteByDocument_Id(Long documentId);
    List<Annotation> findByDocument_IdAndStatus(Long documentId, Annotation.AnnotationStatus status);
}
