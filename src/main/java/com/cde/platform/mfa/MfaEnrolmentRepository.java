package com.cde.platform.mfa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Enrolments, always reached by user.
 *
 * <p>No method takes a tenant: Row-Level Security supplies it from the
 * connection's {@code app.tenant_id}, and a repository method that accepted
 * one would invite a caller to pass a tenant from a request parameter.
 */
public interface MfaEnrolmentRepository extends JpaRepository<MfaEnrolment, Long> {

    Optional<MfaEnrolment> findByUserId(Long userId);

    void deleteByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
