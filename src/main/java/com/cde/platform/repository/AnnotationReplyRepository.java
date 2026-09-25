package com.cde.platform.repository;

import com.cde.platform.model.AnnotationReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface AnnotationReplyRepository extends JpaRepository<AnnotationReply, Long> {
    List<AnnotationReply> findByAnnotation_IdOrderByCreatedAtAsc(Long annotationId);

    /**
     * Every reply on a document's markup, in one query.
     *
     * <p>The review panel needs the whole conversation to open, and it used to
     * get it one annotation at a time — a request per thread, which is the
     * N+1 §7.2's "one screen, one request" exists to prevent. A drawing with
     * forty comments on it opened forty connections.
     *
     * <p>Fetching the author is the load-bearing part rather than an
     * optimisation: rendering a reply reads its author's username, so a lazy
     * association turns one query into one per reply — the same N+1 moved from
     * HTTP down into SQL, where nobody watching the network tab would see it.
     * `AnnotationReplyRepositoryTest` counts the statements and fails if that
     * join is dropped.
     *
     * <p>The annotation is joined either way, because the filter is on its
     * document. `fetch` on it is not currently load-bearing — the mapper reads
     * only its id, which a lazy proxy serves without initialising, so removing
     * `fetch` passes the statement count. It is kept because the row is being
     * read regardless and the proxy buys nothing: the first person to read any
     * other field of the annotation would otherwise reintroduce the N+1, and
     * the statement-count test would catch it then rather than now.
     */
    @Query("""
        select reply from AnnotationReply reply
        join fetch reply.annotation annotation
        left join fetch reply.author
        where annotation.document.id = :documentId
        order by reply.createdAt asc""")
    List<AnnotationReply> findForDocument(@Param("documentId") Long documentId);

    void deleteByAnnotation_IdIn(java.util.Collection<Long> annotationIds);
}
