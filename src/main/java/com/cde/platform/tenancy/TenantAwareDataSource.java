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
            applyTenantSetting(connection);
        } catch (SQLException e) {
            // A connection that could not be constrained must not be handed
            // out: it would run as the unrestricted role with no tenant filter,
            // which is the exact failure this class exists to prevent.
            closeQuietly(connection);
            throw e;
        }
        return proxy(connection);
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

    private void applyTenantSetting(Connection connection) throws SQLException {
        // set_config rather than SET, because SET takes no parameter marker and
        // would mean interpolating the tenant id into SQL.
        try (PreparedStatement statement =
                 connection.prepareStatement("SELECT set_config(?, ?, false)")) {
            statement.setString(1, TENANT_SETTING);
            statement.setString(2, TenantContext.currentTenantId()
                .map(String::valueOf)
                // Empty rather than null: set_config rejects null for a
                // non-null-able setting. The policies wrap the read in NULLIF
                // because ''::BIGINT raises rather than yielding NULL, so the
                // comparison ends up NULL and matches nothing — the
                // fail-closed path.
                .orElse(""));
            statement.execute();
        }
    }

    private Connection proxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            new ResettingConnectionHandler(connection));
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close a connection that could not be tenant-scoped", e);
        }
    }

    /**
     * Clears the session state this class established, immediately before the
     * connection goes back to the pool.
     */
    private final class ResettingConnectionHandler implements InvocationHandler {

        private final Connection delegate;

        private ResettingConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                resetSessionState();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
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
