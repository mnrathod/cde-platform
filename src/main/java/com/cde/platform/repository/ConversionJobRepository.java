package com.cde.platform.repository;

import com.cde.platform.model.ConversionJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversion jobs, read the way the API asks for them.
 *
 * <p>No method here carries a tenant predicate. That is not an oversight:
 * Row-Level Security supplies it from {@code app.tenant_id}, set centrally
 * from the authenticated principal, and a hand-written
 * {@code AND tenant_id = ?} would be exactly the manual filtering §5.6
 * forbids — it works until the one query that forgets, and RLS has to be the
 * backstop rather than the belt.
 */
public interface ConversionJobRepository extends JpaRepository<ConversionJob, Long> {

    /**
     * The lookup behind every job endpoint. Returns empty for another tenant's
     * job for the same reason it returns empty for one that does not exist —
     * RLS filters it out before this sees a row — so a caller cannot tell the
     * two apart, which is the answer they should get (§5.5).
     */
    Optional<ConversionJob> findByPublicId(UUID publicId);

    Page<ConversionJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Jobs left mid-flight, for startup recovery.
     *
     * <p>These cannot be resumed: the source link was never stored, because it
     * is a presigned bearer credential. They are failed with that reason so a
     * caller polling one gets an answer rather than a job that says RUNNING
     * for ever.
     */
    List<ConversionJob> findByStatusIn(List<ConversionJob.Status> statuses);
}
