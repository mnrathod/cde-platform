package com.cde.platform.tenancy;

import com.cde.platform.model.Tenant;
import com.cde.platform.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Creates the tenant a self-service registration lands in.
 *
 * <p>Its own service rather than a method on the controller because creating a
 * tenant is the act of drawing a new isolation boundary, and the rules for
 * naming one are not obvious enough to inline.
 */
@Service
public class TenantProvisioningService {

    /**
     * Slugs are generated, never derived from the email domain.
     *
     * <p>Deriving them looks friendlier and is a land grab: the first person
     * from a company to sign up would claim {@code acme}, and the second would
     * either collide with a stranger or be handed membership of a tenant they
     * have not been invited to. Claiming a domain requires proving you own it
     * (§4.5), which is home-realm discovery and is not built. Until it is, an
     * opaque slug asserts nothing about who owns what.
     */
    private static final String SLUG_PREFIX = "org-";

    /** 64 bits of slug. Collisions are handled anyway; this makes them rare. */
    private static final int SLUG_BYTES = 8;

    private static final int MAX_SLUG_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepository;

    public TenantProvisioningService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Creates a new, empty tenant.
     *
     * @param requestedName what to call it in the interface; blank falls back
     *                      to a name derived from {@code fallbackOwner}, so
     *                      registration needs no organisation field to work
     * @return the persisted tenant, which is the boundary the caller's account
     *         will be created inside
     */
    @Transactional
    public Tenant provisionFor(String requestedName, String fallbackOwner) {
        String name = (requestedName == null || requestedName.isBlank())
            ? fallbackOwner + "'s organisation"
            : requestedName.trim();

        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String slug = generateSlug();
            if (!tenantRepository.findBySlug(slug).isPresent()) {
                return tenantRepository.save(Tenant.builder()
                    .slug(slug)
                    .name(name.length() > 200 ? name.substring(0, 200) : name)
                    .build());
            }
        }
        // Five collisions on 64 random bits is not bad luck, it is a broken
        // random source, and inventing a sixth slug would paper over that.
        throw new IllegalStateException("Could not allocate a unique tenant slug.");
    }

    private String generateSlug() {
        byte[] material = new byte[SLUG_BYTES];
        RANDOM.nextBytes(material);
        return (SLUG_PREFIX + HexFormat.of().formatHex(material)).toLowerCase(Locale.ROOT);
    }
}
