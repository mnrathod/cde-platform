package com.cde.platform.mfa;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One user's TOTP enrolment.
 *
 * <p>Not a Lombok {@code @Data} entity, deliberately. Generated getters would
 * include one for the encrypted secret, and generated {@code toString} and
 * {@code equals} would include the secret in their output — which is how a
 * credential ends up in a log line or an exception message. Access to the
 * secret goes through {@link #encryptedSecret()} and nowhere else, and
 * {@link #toString()} is written by hand to exclude it.
 */
@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "user_mfa_enrolment")
public class MfaEnrolment implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "encrypted_secret", nullable = false)
    private String encryptedSecret;

    @Column(nullable = false, length = 16)
    private String algorithm = "SHA1";

    @Column(nullable = false)
    private short digits = 6;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "last_used_time_step")
    private Long lastUsedTimeStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MfaEnrolment() {
        // JPA
    }

    public static MfaEnrolment pending(Long userId, String encryptedSecret,
                                       String algorithm, int digits) {
        MfaEnrolment enrolment = new MfaEnrolment();
        enrolment.userId = userId;
        enrolment.encryptedSecret = encryptedSecret;
        enrolment.algorithm = algorithm;
        enrolment.digits = (short) digits;
        return enrolment;
    }

    /**
     * Marks the enrolment live, once the user has proved a code works.
     *
     * <p>Also records the time step that proved it, so the very code used to
     * confirm cannot immediately be replayed as a sign-in.
     */
    public void confirm(long provingTimeStep) {
        if (confirmedAt != null) {
            throw new IllegalStateException("This enrolment is already confirmed");
        }
        this.confirmedAt = Instant.now();
        this.lastUsedTimeStep = provingTimeStep;
    }

    /** Records a successful verification, burning that step against replay. */
    public void recordUse(long timeStep) {
        this.lastUsedTimeStep = timeStep;
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    /** The last step already spent, or -1 when none has been. */
    public long lastUsedTimeStepOrNone() {
        return lastUsedTimeStep == null ? -1L : lastUsedTimeStep;
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

    /** The AES-256-GCM ciphertext. Decryption is the service's job, not a caller's. */
    public String encryptedSecret() {
        return encryptedSecret;
    }

    public String algorithm() {
        return algorithm;
    }

    public int digits() {
        return digits;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    @Override
    public String toString() {
        // No secret, no ciphertext. This string reaches logs and exception
        // messages, and a credential in either is a reportable incident.
        return "MfaEnrolment[id=" + id + ", userId=" + userId
             + ", confirmed=" + isConfirmed() + ']';
    }
}
