package com.cde.platform.invitation;

import com.cde.platform.model.Invitation;
import com.cde.platform.model.User;
import com.cde.platform.repository.InvitationRepository;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Issues and redeems invitations to join a tenant.
 *
 * <p>An invitation is the only way an account is created inside an
 * organisation the caller does not already belong to. Registration refuses to
 * take a tenant identifier from the request body — that endpoint needs no
 * credential, so anything it accepts is something a stranger can assert about
 * themselves. A token issued from inside the tenant is proof rather than an
 * assertion, which is the whole reason this exists.
 */
@Service
public class InvitationService {

    /**
     * Prefixed so a leaked token is recognisable in a log or a paste, and
     * scanners can pattern-match it. 256 bits of entropy, URL-safe.
     */
    private static final String TOKEN_PREFIX = "cdeinv_";

    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final InvitationRepository invitations;
    private final JdbcTemplate jdbcTemplate;
    private final TenancyProperties tenancyProperties;

    public InvitationService(InvitationRepository invitations,
                             JdbcTemplate jdbcTemplate,
                             TenancyProperties tenancyProperties) {
        this.invitations = invitations;
        this.jdbcTemplate = jdbcTemplate;
        this.tenancyProperties = tenancyProperties;
    }

    /**
     * The token and the row it was stored against.
     *
     * <p>The token is returned only here, only once. It is never persisted in
     * a readable form and no endpoint can produce it again — a readable
     * invitation table would be a set of credentials for every pending
     * account.
     */
    public record IssuedInvitation(Invitation invitation, String token) {}

    /**
     * Issues an invitation into the caller's own tenant.
     *
     * <p>The tenant is taken from the caller's bound context, never from a
     * parameter: an administrator can only invite people into the organisation
     * they are themselves inside.
     */
    @Transactional
    public IssuedInvitation invite(String email, User.Role role, Long issuedBy) {
        String token = generateToken();
        LocalDateTime now = LocalDateTime.now();

        Invitation invitation = invitations.save(Invitation.builder()
            .tenantId(TenantContext.requireTenantId())
            .email(email.trim())
            .role(role)
            .tokenHash(hash(token))
            .expiresAt(now.plus(tenancyProperties.getInvitationValidity()))
            .createdBy(issuedBy)
            .createdAt(now)
            .build());

        return new IssuedInvitation(invitation, token);
    }

    /** Every invitation issued by the caller's tenant, newest first. */
    public List<Invitation> issuedByCallerTenant() {
        return invitations.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Withdraws an unredeemed invitation.
     *
     * @return false when there is no such invitation in the caller's tenant,
     *         which is also what a caller sees for one belonging to another
     *         tenant — the row is invisible under the policy, so this cannot
     *         distinguish the two, and should not
     */
    @Transactional
    public boolean revoke(Long invitationId) {
        return invitations.findById(invitationId)
            .filter(invitation -> invitation.getAcceptedAt() == null)
            .map(invitation -> {
                invitation.setRevokedAt(LocalDateTime.now());
                invitations.save(invitation);
                return true;
            })
            .orElse(false);
    }

    /**
     * Resolves which tenant a token belongs to, before any context is bound.
     *
     * <p>Redemption happens on an unauthenticated request, so this hits the
     * same circularity as login: the invitation is behind the tenant policy,
     * but reading it is how the tenant gets established. The way through is
     * the same — a {@code SECURITY DEFINER} function returning exactly one
     * {@code BIGINT}, which cannot disclose the invited address, the role, or
     * who issued it.
     *
     * @return the tenant owning a currently redeemable token, or empty
     */
    public Optional<Long> resolveTenantFor(String token) {
        Long tenantId = jdbcTemplate.queryForObject(
            "SELECT resolve_tenant_for_invitation(?)", Long.class, hash(token));
        return Optional.ofNullable(tenantId);
    }

    /**
     * Loads a redeemable invitation, with the tenant already bound.
     *
     * <p>Everything the resolver above deliberately did not disclose is
     * re-checked here, reading the row under the policy in the normal way.
     * The expiry and single-use conditions are checked twice — once in SQL to
     * resolve the tenant, once here — because the first is a lookup key and
     * the second is the decision.
     */
    public Optional<Invitation> findRedeemable(String token) {
        return invitations.findByTokenHash(hash(token))
            .filter(invitation -> invitation.isRedeemable(LocalDateTime.now()));
    }

    /** Marks an invitation used, so a second attempt with it is refused. */
    @Transactional
    public void markAccepted(Invitation invitation, Long acceptedBy) {
        invitation.setAcceptedAt(LocalDateTime.now());
        invitation.setAcceptedBy(acceptedBy);
        invitations.save(invitation);
    }

    private String generateToken() {
        byte[] material = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(material);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * SHA-256, hex.
     *
     * <p>Not a password KDF, and deliberately not: a KDF's cost exists to make
     * guessing a low-entropy secret expensive, and this secret is 256 random
     * bits. There is nothing to guess, so the iteration count would buy
     * nothing and cost a lookup. This is the treatment the platform already
     * gives API keys and refresh tokens, for the same reason.
     */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and was not available.", e);
        }
    }
}
