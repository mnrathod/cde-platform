package com.cde.platform.invitation;

import com.cde.platform.model.Invitation;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.tenancy.TenantProvisioningService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Decides which tenant a self-service registration lands in, and creates the
 * account there.
 *
 * <p>Extracted from the controller because the decision has three outcomes and
 * a security argument behind each, and a controller is supposed to validate and
 * delegate. The rule it enforces is one sentence: a caller may create an
 * account in an organisation it can prove it was invited to, or in a brand new
 * one, and never in an existing organisation it merely named.
 */
@Service
public class RegistrationService {

    /**
     * Why a registration was refused, in the caller's terms.
     *
     * <p>Modelled as an outcome rather than thrown, because two of these are
     * ordinary answers to a well-formed request — a taken username is not an
     * exceptional condition — and the controller has to turn each into a
     * different status code anyway.
     */
    public sealed interface Outcome {

        /** The account exists. The token is for it. */
        record Registered(User user, Tenant tenant, boolean joinedByInvitation) implements Outcome {}

        /** The username or email is already in use somewhere in the deployment. */
        record IdentityTaken() implements Outcome {}

        /** Self-service registration is switched off for this deployment. */
        record RegistrationClosed() implements Outcome {}

        /** An invitation is required here and none was presented. */
        record InvitationRequired() implements Outcome {}

        /**
         * The invitation was not usable: unknown, expired, revoked, already
         * redeemed, or issued to a different address.
         *
         * <p>One outcome for all of those on purpose. Distinguishing "expired"
         * from "no such invitation" tells someone holding a guessed token
         * whether they guessed a real one.
         */
        record InvitationNotUsable() implements Outcome {}
    }

    private final UserRepository users;
    private final TenantRepository tenants;
    private final InvitationService invitations;
    private final TenantProvisioningService provisioning;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbcTemplate;
    private final TenancyProperties tenancyProperties;

    public RegistrationService(UserRepository users,
                               TenantRepository tenants,
                               InvitationService invitations,
                               TenantProvisioningService provisioning,
                               PasswordEncoder encoder,
                               JdbcTemplate jdbcTemplate,
                               TenancyProperties tenancyProperties) {
        this.users = users;
        this.tenants = tenants;
        this.invitations = invitations;
        this.provisioning = provisioning;
        this.encoder = encoder;
        this.jdbcTemplate = jdbcTemplate;
        this.tenancyProperties = tenancyProperties;
    }

    public Outcome register(String username, String email, String rawPassword,
                            String invitationToken, String organisationName) {

        if (tenancyProperties.getSelfRegistration() == TenancyProperties.SelfRegistration.DISABLED) {
            return new Outcome.RegistrationClosed();
        }

        // Checked before anything is created, and checked across the whole
        // deployment rather than within one tenant. username and email are
        // globally unique because login resolves the tenant from the username
        // alone, so an in-tenant check would pass and the insert would then
        // fail on the constraint — a name clash surfacing as a 500.
        if (isIdentityTaken(username, email)) {
            return new Outcome.IdentityTaken();
        }

        return invitationToken == null || invitationToken.isBlank()
            ? registerIntoNewTenant(username, email, rawPassword, organisationName)
            : joinByInvitation(username, email, rawPassword, invitationToken);
    }

    private Outcome registerIntoNewTenant(String username, String email,
                                          String rawPassword, String organisationName) {
        if (tenancyProperties.getSelfRegistration()
                == TenancyProperties.SelfRegistration.INVITATION_ONLY) {
            return new Outcome.InvitationRequired();
        }

        Tenant tenant = provisioning.provisionFor(organisationName, username);

        // ADMIN, because somebody has to be able to invite the second person
        // into an organisation that currently has one member, and there is
        // nobody else to grant it. This is not an escalation: the tenant was
        // created empty by this same request, so the authority is over
        // nothing but what the caller is about to put there.
        User user = createUserIn(tenant, username, email, rawPassword, User.Role.ADMIN);
        return new Outcome.Registered(user, tenant, false);
    }

    private Outcome joinByInvitation(String username, String email,
                                     String rawPassword, String token) {
        // The tenant comes from the token, resolved by a SECURITY DEFINER
        // function, because an unauthenticated caller cannot read the
        // invitation that would establish the context needed to read it.
        var tenantId = invitations.resolveTenantFor(token);
        if (tenantId.isEmpty()) {
            return new Outcome.InvitationNotUsable();
        }

        return TenantContext.callAsTenant(tenantId.get(), () -> {
            var found = invitations.findRedeemable(token);
            if (found.isEmpty()) {
                return new Outcome.InvitationNotUsable();
            }

            Invitation invitation = found.get();

            // The address is the binding, not the token. Without this, an
            // invitation forwarded to a colleague — or leaked from an inbox —
            // admits whoever opens it.
            if (!invitation.admits(email)) {
                return new Outcome.InvitationNotUsable();
            }

            Tenant tenant = tenants.findById(invitation.getTenantId()).orElseThrow(
                () -> new IllegalStateException(
                    "Invitation " + invitation.getId() + " names a tenant that is not there."));

            User user = createUserIn(tenant, username, email, rawPassword, invitation.getRole());
            invitations.markAccepted(invitation, user.getId());

            return new Outcome.Registered(user, tenant, true);
        });
    }

    private User createUserIn(Tenant tenant, String username, String email,
                              String rawPassword, User.Role role) {
        return TenantContext.callAsTenant(tenant.getId(), () -> users.save(User.builder()
            .username(username)
            .email(email)
            .password(encoder.encode(rawPassword))
            .role(role)
            .tenantId(tenant.getId())
            .build()));
    }

    /**
     * Whether the username or email is in use anywhere in the deployment.
     *
     * <p>Answered by a {@code SECURITY DEFINER} function returning a boolean,
     * for the reason above. It discloses no more than the conflict response
     * registration already returns for a taken name.
     */
    private boolean isIdentityTaken(String username, String email) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT registration_identity_taken(?, ?)", Boolean.class, username, email));
    }
}
