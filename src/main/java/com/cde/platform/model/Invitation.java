package com.cde.platform.model;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An offer to join one tenant, issued from inside it.
 *
 * <p>This is what lets registration refuse to take a tenant identifier from
 * the caller while still allowing anyone to join an existing organisation. A
 * tenant named in a request body is an assertion by a stranger; an invitation
 * is proof, because only an administrator of that tenant could have created it.
 *
 * <p>The token is held as a SHA-256 hash and shown exactly once, at creation.
 * A readable invitation table would be a set of credentials for every pending
 * account, which is the same reasoning applied to API keys.
 */
@Entity
@Table(name = "invitations")
@EntityListeners(TenantAssigningListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invitation implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * The address this invitation admits, and only this address. A token that
     * admits whoever holds it turns a forwarded email into an account in
     * someone else's organisation.
     */
    @Column(nullable = false, length = 254)
    private String email;

    /** Chosen by the inviting administrator, never by the person redeeming it. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private User.Role role;

    /** Hex SHA-256 of the token. The token itself is not recoverable. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Whether this invitation can still be redeemed.
     *
     * <p>Expressed as one predicate rather than three checks at the call site,
     * because "redeemable" is the question every caller actually has and three
     * separate checks are three chances to forget one.
     */
    public boolean isRedeemable(LocalDateTime now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    /** Whether this invitation admits the given address, ignoring case. */
    public boolean admits(String candidateEmail) {
        return candidateEmail != null && email.equalsIgnoreCase(candidateEmail.trim());
    }

    /** The status to show an administrator listing pending invitations. */
    public String describeStatus(LocalDateTime now) {
        if (acceptedAt != null) return "ACCEPTED";
        if (revokedAt != null) return "REVOKED";
        return expiresAt.isAfter(now) ? "PENDING" : "EXPIRED";
    }
}
