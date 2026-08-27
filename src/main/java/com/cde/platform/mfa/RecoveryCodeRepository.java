package com.cde.platform.mfa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    /**
     * Finds an unredeemed code by its digest.
     *
     * <p>The {@code usedAt IS NULL} predicate is part of the query rather than
     * a check afterwards, so a used code cannot be found and then rejected by
     * a branch someone might later remove.
     */
    Optional<RecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(Long userId, String codeHash);

    List<RecoveryCode> findByUserId(Long userId);

    long countByUserIdAndUsedAtIsNull(Long userId);

    void deleteByUserId(Long userId);
}
