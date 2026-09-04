package com.cde.platform.security;

import com.cde.platform.cde.domain.ContainerPermission;
import com.cde.platform.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which permissions each role holds — the single place the mapping is written.
 *
 * <p>This class knows the whole platform's permission vocabulary, and that is
 * deliberate rather than a layering slip. A role is precisely a named bundle of
 * permissions drawn from every feature; something has to hold that list, and
 * scattering it across the features would make "what can a reviewer actually
 * do?" a question you answer by grepping.
 *
 * <p>The role set here is the fixed one the platform ships with. Tenant-defined
 * custom roles (§5.5) are not built yet: when they are, they belong in a table
 * that this class reads, with these entries as the seeded defaults — the shape
 * of the mapping does not change, only where it is stored.
 *
 * <p>The assignments follow the ISO 19650 division of labour rather than
 * seniority. An engineer originates information and issues it for coordination;
 * a reviewer authorises it or sends it back. Neither is a superset of the
 * other, which is the point of modelling permissions separately from rank:
 * a reviewer cannot create a container, and an engineer cannot publish one.
 */
public final class RolePermissions {

    private RolePermissions() {
    }

    /**
     * Members of a task team: they originate information, issue it for
     * coordination, and abandon their own work in progress. They cannot
     * authorise it for use — publication is somebody else's signature.
     */
    private static final Set<String> ENGINEER_PERMISSIONS = Set.of(
        ContainerPermission.READ,
        ContainerPermission.WRITE,
        ContainerPermission.SHARE,
        ContainerPermission.ARCHIVE,
        // Converting a drawing to PDF produces a derivative, which is
        // originating information rather than authorising it — the same side
        // of the ISO 19650 division of labour as WRITE.
        ConversionPermission.SUBMIT);

    /**
     * The lead appointed party's reviewers: they authorise or reject what has
     * been shared. They do not originate containers, so {@code WRITE} is
     * absent — and because superseding a published revision issues a new one,
     * a reviewer cannot supersede either.
     */
    private static final Set<String> REVIEWER_PERMISSIONS = Set.of(
        ContainerPermission.READ,
        ContainerPermission.SHARE,
        ContainerPermission.PUBLISH,
        ContainerPermission.REJECT,
        ContainerPermission.ARCHIVE);

    private static final Set<String> VIEWER_PERMISSIONS = Set.of(
        ContainerPermission.READ);

    /**
     * Everything, including the authority to decide who is in the tenant.
     *
     * <p>Built by union rather than written out, so a permission added to
     * any vocabulary reaches the administrator without anyone remembering to
     * add it here — the failure mode otherwise is an administrator who cannot
     * use a feature, diagnosed as a bug in the feature.
     */
    private static final Set<String> ADMIN_PERMISSIONS =
        Stream.of(ContainerPermission.ALL, TenantPermission.ALL, ConversionPermission.ALL)
              .flatMap(Set::stream)
              .collect(Collectors.toUnmodifiableSet());

    public static Set<String> grantedTo(User.Role role) {
        if (role == null) {
            // A row with no role gets nothing. Defaulting to the least
            // privileged role would look like a sensible fallback and would
            // quietly grant read access to an account whose role failed to
            // load.
            return Set.of();
        }
        return switch (role) {
            // The only role that may decide who is inside the tenant at all.
            // An engineer publishes nothing and a reviewer originates nothing,
            // but either holding this would let them add an account that does.
            case ADMIN -> ADMIN_PERMISSIONS;
            case ENGINEER -> ENGINEER_PERMISSIONS;
            case REVIEWER -> REVIEWER_PERMISSIONS;
            case VIEWER -> VIEWER_PERMISSIONS;
        };
    }

    /**
     * The authorities to put on the authenticated principal: the permissions
     * above, plus the {@code ROLE_} authority the existing role-based rules
     * still match on.
     *
     * <p>Both, not one or the other. Dropping the {@code ROLE_} authority would
     * silently open or close the actuator rules that are written in terms of
     * it, and those failures do not surface until something scrapes metrics.
     */
    public static List<GrantedAuthority> authoritiesFor(User.Role role) {
        var authorities = new java.util.ArrayList<GrantedAuthority>();
        if (role != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        }
        grantedTo(role).stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        return List.copyOf(authorities);
    }
}
