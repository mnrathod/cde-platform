package com.cde.platform.support;

import com.cde.platform.model.User;
import com.cde.platform.security.RolePermissions;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a test as a principal carrying exactly what the named role really
 * grants.
 *
 * <p>Replaces {@code @WithMockUser(roles = "ENGINEER")}, which grants a single
 * {@code ROLE_ENGINEER} authority and nothing else. That was fine while the
 * document endpoints checked nothing, and it is why the gap this annotation
 * exists to close could sit unnoticed: the tests authenticated as a principal
 * no real sign-in produces, so they could not have noticed that the
 * permissions each endpoint documents were enforced nowhere.
 *
 * <p>The authorities come from {@link RolePermissions#authoritiesFor}, the same
 * method {@code SecurityConfig} calls to build a real principal. That is the
 * point of the indirection: a permission removed from a role breaks the tests
 * that rely on it, instead of them continuing to pass against a list written
 * out by hand in a test file and never revisited.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@WithSecurityContext(factory = ActingAs.Factory.class)
public @interface ActingAs {

    /** The role whose real grants the principal carries. */
    User.Role value();

    /** Username to authenticate as. */
    String username() default "test-user";

    final class Factory implements WithSecurityContextFactory<ActingAs> {

        @Override
        public SecurityContext createSecurityContext(ActingAs acting) {
            var authorities = RolePermissions.grantedTo(acting.value()).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
            // Both the permissions and the ROLE_ authority, matching
            // RolePermissions.authoritiesFor — rules written against either
            // resolve, and a test cannot pass because it happened to be given
            // only the shape the endpoint under test looks at.
            var all = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>(
                authorities);
            all.add(new SimpleGrantedAuthority("ROLE_" + acting.value().name()));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                org.springframework.security.core.userdetails.User
                    .withUsername(acting.username())
                    .password("irrelevant")
                    .authorities(all)
                    .build(),
                null, all));
            return context;
        }
    }
}
