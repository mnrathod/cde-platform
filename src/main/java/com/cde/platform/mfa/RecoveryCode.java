package com.cde.platform.mfa;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One single-use recovery code, stored as a digest.
 *
 * <p>Marked used rather than deleted, so that redeeming one is visible in the
 * audit trail and the user can be told how many remain. A deleted row answers
 * neither question.
 */
@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "user_recovery_code")
public class RecoveryCode implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RecoveryCode() {
        // JPA
    }

    public static RecoveryCode of(Long userId, String codeHash) {
        RecoveryCode code = new RecoveryCode();
        code.userId = userId;
        code.codeHash = codeHash;
        return code;
    }

    public void markUsed() {
        if (usedAt != null) {
            throw new IllegalStateException("This recovery code has already been used");
        }
        this.usedAt = Instant.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long userId() {
        return userId;
    }

    public Instant usedAt() {
        return usedAt;
    }

    @Override
    public String toString() {
        // The hash is omitted: it is the lookup key for redemption, and a hash
        // in a log invites an offline search against a known code format.
        return "RecoveryCode[id=" + id + ", userId=" + userId + ", used=" + isUsed() + ']';
    }
}
