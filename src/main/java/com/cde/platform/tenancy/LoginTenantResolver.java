package com.cde.platform.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves which tenant a login attempt belongs to, before authentication.
 *
 * <p>This exists to break a circularity. Tenant context is derived from the
 * user, the user is read from the {@code users} table, and that table is behind
 * the tenant policy — so an unauthenticated login request cannot read the row
 * it needs to establish the context that would let it read that row.
 *
 * <p>The way through is a {@code SECURITY DEFINER} function in the database
 * that returns exactly one {@code BIGINT}. It cannot return a password hash, an
 * email address, or a row belonging to anyone: the single fact it discloses is
 * which tenant owns a given username. Callers must not pass that fact on — a
 * login response that distinguishes "no such user" from "wrong password" is a
 * user-enumeration oracle.
 */
@Service
public class LoginTenantResolver {

    private final JdbcTemplate jdbcTemplate;

    public LoginTenantResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return the tenant owning {@code username}, or empty when no such user
     *         exists. The caller must respond identically in both cases.
     */
    public Optional<Long> resolveFor(String username) {
        Long tenantId = jdbcTemplate.queryForObject(
            "SELECT resolve_tenant_for_login(?)", Long.class, username);
        return Optional.ofNullable(tenantId);
    }
}
