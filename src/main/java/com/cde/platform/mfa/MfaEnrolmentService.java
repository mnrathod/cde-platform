package com.cde.platform.mfa;

import com.cde.platform.audit.AuditAction;
import com.cde.platform.audit.AuditOutcome;
import com.cde.platform.audit.AuditRequest;
import com.cde.platform.audit.AuditTrailService;
import com.cde.platform.audit.AuditableChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Enrolling a second factor, and checking one.
 *
 * <p>Three properties are worth stating because each is a way this goes wrong
 * when built casually:
 *
 * <ul>
 *   <li><strong>An enrolment is not a factor until a code has been verified.</strong>
 *       Activating on "the QR code was displayed" locks people out of their own
 *       accounts whenever the scan silently failed or the phone's clock is wrong.
 *   <li><strong>The secret is never returned after enrolment.</strong> It is
 *       shown once, during setup, and after that no API path can retrieve it.
 *       An endpoint that returns it turns any session hijack into a permanent
 *       second-factor compromise.
 *   <li><strong>Every verification burns its time step.</strong> Without that,
 *       a code seen over a shoulder stays usable for its whole window.
 * </ul>
 */
@Service
// Gated with the rest of the feature: without an encryption key there is no
// SecretEncryption bean to inject, and a deployment that has not provisioned
// one should start without the second factor rather than fail to start.
@ConditionalOnProperty(prefix = "cde.mfa", name = "enabled", havingValue = "true")
public class MfaEnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(MfaEnrolmentService.class);

    /** §4.4: ten codes, shown once. */
    static final int RECOVERY_CODE_COUNT = 10;

    /**
     * 10 characters from a 32-symbol alphabet is 50 bits — far past guessing,
     * and short enough to write down accurately, which is the entire point of
     * a recovery code.
     */
    private static final int RECOVERY_CODE_LENGTH = 10;

    /**
     * Crockford-style: no 0, 1, 8, I, L, O or U. The first four are confusable
     * when handwritten, and dropping U removes most accidental profanity from
     * a code a user has to read aloud to support.
     */
    private static final String RECOVERY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final MfaEnrolmentRepository enrolments;
    private final RecoveryCodeRepository recoveryCodes;
    private final SecretEncryption encryption;
    private final TimeBasedOneTimePassword totp;
    private final AuditTrailService audit;
    private final SecureRandom random = new SecureRandom();
    private final String issuerName;

    public MfaEnrolmentService(MfaEnrolmentRepository enrolments,
                               RecoveryCodeRepository recoveryCodes,
                               SecretEncryption encryption,
                               TimeBasedOneTimePassword totp,
                               AuditTrailService audit,
                               @Value("${cde.mfa.issuer:CDE Platform}") String issuerName) {
        this.enrolments = enrolments;
        this.recoveryCodes = recoveryCodes;
        this.encryption = encryption;
        this.totp = totp;
        this.audit = audit;
        this.issuerName = issuerName;
    }

    /**
     * Begins enrolment: a fresh secret, and the URI an authenticator scans.
     *
     * <p>Replaces any unconfirmed enrolment for this user. A confirmed one is
     * refused — changing a live second factor is a separate, step-up-protected
     * operation, not something a repeated setup request should quietly do.
     */
    @Transactional
    public EnrolmentInvitation beginEnrolment(long userId, String username) {
        enrolments.findByUserId(userId).ifPresent(existing -> {
            if (existing.isConfirmed()) {
                throw new MfaAlreadyEnrolledException(userId);
            }
            // Abandoned setup. Replacing it is right: the old secret was never
            // proved to work and may not exist in any authenticator app.
            enrolments.delete(existing);
            // Flushed explicitly. JPA orders inserts ahead of deletes in its
            // action queue, so without this the new row is inserted while the
            // old one is still present and the one-enrolment-per-user
            // constraint fires — a restarted setup would fail with a
            // constraint violation rather than replacing the abandoned row.
            enrolments.flush();
        });

        byte[] secret = encryption.generateTotpSecret();
        MfaEnrolment enrolment = MfaEnrolment.pending(
            userId, encryption.encrypt(secret), totp.algorithmLabel(), totp.digits());
        enrolments.save(enrolment);

        audit.record(AuditRequest.by(userId, username)
            .did(AuditAction.MFA_ENROLMENT_STARTED)
            .to("user", userId)
            // The algorithm and digit count, never the secret. AuditableChange
            // refuses a field whose name looks like a credential, which is a
            // guard rather than a reason to be careless here.
            .changing(AuditableChange.of("algorithm", totp.algorithmLabel())
                .and("digits", totp.digits()))
            .build());

        return new EnrolmentInvitation(
            Base32.encode(secret),
            otpAuthUri(username, secret),
            totp.digits(),
            totp.timeStepSeconds());
    }

    /**
     * Completes enrolment once the user proves a code works, returning their
     * recovery codes.
     *
     * <p>This is the only time the recovery codes exist in plaintext. Only
     * their digests are stored, so a lost set means regenerating rather than
     * recovering — which is the property that makes a database dump useless.
     */
    @Transactional
    public List<String> confirmEnrolment(long userId, String username, String presentedCode) {
        MfaEnrolment enrolment = enrolments.findByUserId(userId)
            .orElseThrow(() -> new MfaNotEnrolledException(userId));

        if (enrolment.isConfirmed()) {
            throw new MfaAlreadyEnrolledException(userId);
        }

        OptionalLong step = totp.verify(
            encryption.decrypt(enrolment.encryptedSecret()),
            presentedCode, Instant.now(), enrolment.lastUsedTimeStepOrNone());

        if (step.isEmpty()) {
            audit.record(AuditRequest.by(userId, username)
                .did(AuditAction.MFA_ENROLMENT_CONFIRMED)
                .outcome(AuditOutcome.FAILURE)
                .to("user", userId)
                .build());
            throw new InvalidMfaCodeException();
        }

        enrolment.confirm(step.getAsLong());
        enrolments.save(enrolment);

        List<String> issued = issueRecoveryCodes(userId);

        audit.record(AuditRequest.by(userId, username)
            .did(AuditAction.MFA_ENROLMENT_CONFIRMED)
            .to("user", userId)
            // "codesIssued", not "recoveryCodes": AuditableChange refuses any
            // field name containing "recoverycode", and it is right to — the
            // count is safe but the name is one careless edit from the value.
            .changing(AuditableChange.of("codesIssued", issued.size()))
            .build());

        return issued;
    }

    /**
     * Checks a code at sign-in or for a step-up.
     *
     * @return true only for a valid, unspent code from a confirmed enrolment
     */
    @Transactional
    public boolean verify(long userId, String username, String presentedCode) {
        Optional<MfaEnrolment> found = enrolments.findByUserId(userId);
        if (found.isEmpty() || !found.get().isConfirmed()) {
            // Not enrolled is not "verified". A caller that treats a missing
            // enrolment as a pass has removed the factor for everyone who
            // never set one up.
            return false;
        }

        MfaEnrolment enrolment = found.get();
        OptionalLong step = totp.verify(
            encryption.decrypt(enrolment.encryptedSecret()),
            presentedCode, Instant.now(), enrolment.lastUsedTimeStepOrNone());

        if (step.isEmpty()) {
            audit.record(AuditRequest.by(userId, username)
                .did(AuditAction.MFA_VERIFIED)
                .outcome(AuditOutcome.FAILURE)
                .to("user", userId)
                .build());
            return false;
        }

        enrolment.recordUse(step.getAsLong());
        enrolments.save(enrolment);
        audit.record(AuditRequest.by(userId, username)
            .did(AuditAction.MFA_VERIFIED)
            .to("user", userId)
            .build());
        return true;
    }

    /**
     * Redeems a recovery code, which is single use.
     *
     * <p>Separate from {@link #verify} because the two must not be
     * interchangeable at the call site: a recovery code is a break-glass
     * credential, and every redemption is an event worth alerting on.
     */
    @Transactional
    public boolean redeemRecoveryCode(long userId, String username, String presented) {
        String normalised = normalise(presented);
        Optional<RecoveryCode> found =
            recoveryCodes.findByUserIdAndCodeHashAndUsedAtIsNull(userId, sha256Hex(normalised));

        if (found.isEmpty()) {
            audit.record(AuditRequest.by(userId, username)
                .did(AuditAction.MFA_RECOVERY_REDEEMED)
                .outcome(AuditOutcome.FAILURE)
                .to("user", userId)
                .build());
            return false;
        }

        RecoveryCode code = found.get();
        code.markUsed();
        recoveryCodes.save(code);

        long remaining = recoveryCodes.countByUserIdAndUsedAtIsNull(userId);
        audit.record(AuditRequest.by(userId, username)
            .did(AuditAction.MFA_RECOVERY_REDEEMED)
            .to("user", userId)
            .changing(AuditableChange.of("remaining", remaining))
            .build());

        if (remaining == 0) {
            // Worth a warning: the user has no break-glass credential left and
            // a lost authenticator now means an admin reset.
            log.warn("User {} has redeemed their last recovery code", userId);
        }
        return true;
    }

    /** Fresh codes, invalidating every previous one. */
    @Transactional
    public List<String> regenerateRecoveryCodes(long userId, String username) {
        if (!enrolments.existsByUserId(userId)) {
            throw new MfaNotEnrolledException(userId);
        }
        recoveryCodes.deleteByUserId(userId);
        List<String> issued = issueRecoveryCodes(userId);

        audit.record(AuditRequest.by(userId, username)
            .did(AuditAction.MFA_RECOVERY_REGENERATED)
            .to("user", userId)
            .changing(AuditableChange.of("codesIssued", issued.size()))
            .build());
        return issued;
    }

    /** Removes the second factor entirely. Step-up protected at the endpoint. */
    @Transactional
    public void disable(long userId, String username) {
        enrolments.deleteByUserId(userId);
        recoveryCodes.deleteByUserId(userId);
        audit.record(AuditRequest.by(userId, username)
            .did(AuditAction.MFA_DISABLED)
            .to("user", userId)
            .build());
    }

    /** Whether this user has a live second factor. */
    @Transactional(readOnly = true)
    public boolean isEnrolled(long userId) {
        return enrolments.findByUserId(userId).filter(MfaEnrolment::isConfirmed).isPresent();
    }

    /** How many break-glass codes remain unspent. */
    @Transactional(readOnly = true)
    public long remainingRecoveryCodes(long userId) {
        return recoveryCodes.countByUserIdAndUsedAtIsNull(userId);
    }

    private List<String> issueRecoveryCodes(long userId) {
        List<String> plaintext = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int issued = 0; issued < RECOVERY_CODE_COUNT; issued++) {
            String code = generateRecoveryCode();
            plaintext.add(code);
            recoveryCodes.save(RecoveryCode.of(userId, sha256Hex(code)));
        }
        return List.copyOf(plaintext);
    }

    private String generateRecoveryCode() {
        StringBuilder code = new StringBuilder(RECOVERY_CODE_LENGTH);
        for (int position = 0; position < RECOVERY_CODE_LENGTH; position++) {
            code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Strips the formatting a user sees and types back.
     *
     * <p>Codes are displayed grouped with a hyphen, so a user typing what they
     * were shown must succeed. Refusing their own formatting is the kind of
     * detail that turns a recovery flow into a support call at exactly the
     * moment the user has already lost their phone.
     */
    private static String normalise(String presented) {
        return presented == null ? ""
            : presented.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * The {@code otpauth://} URI an authenticator app reads from a QR code.
     *
     * <p>The label carries the issuer as well as the account so that a user
     * with several accounts can tell them apart in the app — a list of
     * identical usernames is unusable.
     */
    private String otpAuthUri(String username, byte[] secret) {
        String issuer = URLEncoder.encode(issuerName, StandardCharsets.UTF_8);
        String label = issuer + ':' + URLEncoder.encode(username, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label
            + "?secret=" + Base32.encode(secret)
            + "&issuer=" + issuer
            + "&algorithm=" + totp.algorithmLabel()
            + "&digits=" + totp.digits()
            + "&period=" + totp.timeStepSeconds();
    }

    /**
     * What the user needs to complete setup.
     *
     * <p>Returned once, at enrolment, and never retrievable afterwards.
     */
    public record EnrolmentInvitation(
        String base32Secret,
        String otpAuthUri,
        int digits,
        int periodSeconds) {

        @Override
        public String toString() {
            // The secret must not reach a log through an accidental
            // interpolation of this record.
            return "EnrolmentInvitation[digits=" + digits + ", period=" + periodSeconds + ']';
        }
    }
}
