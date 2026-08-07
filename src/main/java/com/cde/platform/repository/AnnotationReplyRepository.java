package com.cde.platform.repository;

import com.cde.platform.model.AnnotationReply;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnnotationReplyRepository extends JpaRepository<AnnotationReply, Long> {
    List<AnnotationReply> findByAnnotation_IdOrderByCreatedAtAsc(Long annotationId);

    void deleteByAnnotation_IdIn(java.util.Collection<Long> annotationIds);
}
