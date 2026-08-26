package com.cde.platform.repository;

import com.cde.platform.model.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    /**
     * Finds an invitation by token hash.
     *
     * <p>No tenant predicate, deliberately: Row-Level Security supplies it, and
     * a hand-written {@code AND tenant_id = ?} here would be the manual
     * filtering the isolation rules forbid. The caller binds the tenant first —
     * for redemption that comes from {@code resolve_tenant_for_invitation},
     * which is the only way an unauthenticated request can establish it.
     */
    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findAllByOrderByCreatedAtDesc();
}
