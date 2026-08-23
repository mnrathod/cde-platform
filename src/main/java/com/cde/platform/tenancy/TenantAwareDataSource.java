package com.cde.platform.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies tenant isolation to every pooled connection, in one place.
 *
 * <p>Two things happen on acquisition, and both are required:
 *
 * <ol>
 *   <li>{@code SET ROLE} to the restricted application role. This is what makes
 *       the Row-Level Security policies apply at all. PostgreSQL exempts
 *       superusers and BYPASSRLS roles from RLS unconditionally and silently —
 *       {@code FORCE ROW LEVEL SECURITY} does not change that, it only binds the
 *       table owner. A deployment whose datasource user happens to be a
 *       superuser, which is the default for a containerised PostgreSQL, has
 *       every policy in place and no isolation whatsoever.</li>
 *   <li>{@code app.tenant_id} is set from {@link TenantContext}. Every policy
 *       reads it; unset means no rows match, in either direction.</li>
 * </ol>
 *
 * <p>Both are reset when the connection returns to the pool. Session state
 * survives a pooled connection's {@code close()}, so a connection returned
 * still carrying a tenant would hand that tenant's visibility to whichever
 * unrelated request borrowed it next.
 *
 * <p>Flyway must not go through here — migrations need the owner's DDL rights,
 * which the restricted role does not have. It is given its own DataSource in
 * configuration.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    private static final String TENANT_SETTING = "app.tenant_id";

    private final String applicationRole;

    public TenantAwareDataSource(DataSource delegate, String applicationRole) {
        super(delegate);
        this.applicationRole = applicationRole;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(requireDelegate().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(requireDelegate().getConnection(username, password));
    }

    private DataSource requireDelegate() {
        DataSource delegate = getTargetDataSource();
        if (delegate == null) {
            throw new IllegalStateException("No target DataSource set");
        }
        return delegate;
    }

    private Connection wrap(Connection connection) throws SQLException {
        try {
            assumeApplicationRole(connection);
            String applied = applyTenantSetting(connection);
            return proxy(connection, applied);
        } catch (SQLException e) {
            // A connection that could not be constrained must not be handed
            // out: it would run as the unrestricted role with no tenant filter,
            // which is the exact failure this class exists to prevent.
            closeQuietly(connection);
            throw e;
        }
    }

    private void assumeApplicationRole(Connection connection) throws SQLException {
        // Identifier, not a value, so it cannot be bound as a parameter. The
        // name comes from validated configuration and never from a request;
        // it is quoted so an odd but legitimate role name still works.
        String quoted = "\"" + applicationRole.replace("\"", "\"\"") + "\"";
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + quoted);
        }
    }

    /**
     * @return the value written, so the caller can tell later whether the
     *         connection has drifted from the currently bound tenant.
     */
    private String applyTenantSetting(Connection connection) throws SQLException {
        String tenant = currentTenant();
        // set_config rather than SET, because SET takes no parameter marker and
        // would mean interpolating the tenant id into SQL.
        try (PreparedStatement statement =
                 connection.prepareStatement("SELECT set_config(?, ?, false)")) {
            statement.setString(1, TENANT_SETTING);
            statement.setString(2, tenant);
            statement.execute();
        }
        return tenant;
    }

    /**
     * Empty rather than null: set_config rejects null for a non-nullable
     * setting. The policies wrap the read in NULLIF because {@code ''::BIGINT}
     * raises rather than yielding NULL, so the comparison ends up NULL and
     * matches nothing — the fail-closed path.
     */
    private static String currentTenant() {
        return TenantContext.currentTenantId().map(String::valueOf).orElse("");
    }

    private Connection proxy(Connection connection, String appliedTenant) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            new ResettingConnectionHandler(connection, appliedTenant));
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close a connection that could not be tenant-scoped", e);
        }
    }

    /**
     * Keeps the connection's tenant in step with the bound one, and clears the
     * session state immediately before the connection goes back to the pool.
     */
    private final class ResettingConnectionHandler implements InvocationHandler {

        /**
         * The calls that are about to send SQL. Re-checking the tenant here
         * rather than only on acquisition is what makes the ordering
         * irrelevant.
         */
        private static final java.util.Set<String> ISSUES_SQL =
            java.util.Set.of("createStatement", "prepareStatement", "prepareCall");

        private final Connection delegate;

        /** What {@code app.tenant_id} currently holds on this connection. */
        private String appliedTenant;

        private ResettingConnectionHandler(Connection delegate, String appliedTenant) {
            this.delegate = delegate;
            this.appliedTenant = appliedTenant;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                resetSessionState();
            } else if (ISSUES_SQL.contains(method.getName())) {
                synchroniseTenant();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        /**
         * Scoping a connection only when it is acquired assumes the tenant is
         * always known before the first query. Two flows cannot satisfy that,
         * because for them the tenant <em>is</em> the answer to a query:
         * registration resolves the default tenant, and sign-in resolves the
         * tenant from the username. With {@code open-in-view} the connection
         * that answered those questions stays with the request, so everything
         * after ran unscoped — registration was refused by the policy, and
         * sign-in read no rows and reported bad credentials.
         *
         * <p>Re-applying at statement time removes the ordering requirement
         * altogether rather than asking every future caller to respect it. It
         * costs nothing in the ordinary case: the tenant is bound before the
         * first query, so the values match and no statement is sent.
         */
        private void synchroniseTenant() throws SQLException {
            String current = currentTenant();
            if (!current.equals(appliedTenant)) {
                // Assigned only after it succeeds. A failure here must leave
                // the connection recorded as still carrying the old value, so
                // the next attempt tries again rather than assuming a scope it
                // does not have.
                appliedTenant = applyTenantSetting(delegate);
            }
        }

        private void resetSessionState() {
            if (isUnusable()) {
                // Nothing to reset, and issuing SQL on a closed or broken
                // connection would replace the real error with a confusing one.
                return;
            }
            try (Statement statement = delegate.createStatement()) {
                statement.execute("SELECT set_config('" + TENANT_SETTING + "', '', false)");
                statement.execute("RESET ROLE");
            } catch (SQLException e) {
                // Returning a connection that still carries another tenant's
                // context is a cross-tenant leak, so the connection is broken
                // deliberately rather than recycled dirty. Hikari discards it.
                log.error("Could not clear tenant session state; discarding the connection", e);
                closeQuietly(delegate);
            }
        }

        private boolean isUnusable() {
            try {
                return delegate.isClosed();
            } catch (SQLException e) {
                return true;
            }
        }
    }
}
