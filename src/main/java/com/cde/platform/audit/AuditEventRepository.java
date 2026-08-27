package com.cde.platform.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Reading the trail. There is no delete and no save-over: the application role
 * is granted {@code INSERT} and {@code SELECT} only, so any such method would
 * compile and then fail at the database.
 *
 * <p>Every query here is unfiltered by tenant on purpose. Row-Level Security is
 * the filter, and adding {@code WHERE tenant_id = ?} on top would mean a
 * forgotten one somewhere else looked equally correct in review.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * The record a new one chains from.
     *
     * <p>Ordered by sequence number rather than by id or timestamp: ids come
     * from a sequence shared across tenants, and two records can share a
     * timestamp.
     */
    Optional<AuditEvent> findFirstByOrderBySequenceNumberDesc();

    Page<AuditEvent> findAllByOrderBySequenceNumberDesc(Pageable pageable);

    Page<AuditEvent> findByActionOrderBySequenceNumberDesc(AuditAction action, Pageable pageable);

    /** In chain order, for verification. */
    List<AuditEvent> findAllByOrderBySequenceNumberAsc();

    /**
     * The next position in this tenant's chain.
     *
     * <p>Taken from the table rather than from a sequence because the chain
     * must be contiguous: a database sequence would leave gaps on rollback, and
     * a gap is meant to be evidence of a missing record.
     */
    @Query("SELECT COALESCE(MAX(e.sequenceNumber), 0) FROM AuditEvent e WHERE e.tenantId = :tenantId")
    long highestSequenceNumberFor(@Param("tenantId") Long tenantId);
}
