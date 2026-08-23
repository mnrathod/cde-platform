package com.cde.platform.tenancy;

import com.cde.platform.model.Tenant;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolation that covers the tables it happens to cover today is not a control.
 *
 * <p>The realistic way tenancy fails is not that someone disables it — it is
 * that someone adds the eighth entity six months from now and nobody notices it
 * has no {@code tenant_id}, no policy, and no listener. Every assertion here is
 * about the <em>set</em> of protected things rather than about any particular
 * one, so the gap appears as a build failure naming the new table.
 */
@SpringBootTest
class TenantIsolationCoverageTest {

    private static final String ENTITY_PACKAGE = "com.cde.platform.model";

    /**
     * The one entity that is legitimately not tenant-scoped: it is the table
     * that defines the scope. Listed by name so adding a second exemption is a
     * deliberate edit to this file rather than an oversight.
     */
    private static final Set<Class<?>> NOT_TENANT_SCOPED = Set.of(Tenant.class);

    @Autowired JdbcTemplate jdbcTemplate;

    private List<Class<?>> allEntities() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return scanner.findCandidateComponents(ENTITY_PACKAGE).stream()
            .map(definition -> {
                try {
                    return Class.forName(definition.getBeanClassName());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            })
            .collect(Collectors.toList());
    }

    @Test
    @DisplayName("the scan finds the entities at all")
    void entityScanIsNotVacuous() {
        // Without this, a broken scanner would make every assertion below pass
        // over an empty list — the most comfortable kind of green.
        assertThat(allEntities()).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("every entity is tenant-scoped, or explicitly exempted")
    void everyEntityImplementsTenantScoped() {
        List<String> unscoped = allEntities().stream()
            .filter(type -> !NOT_TENANT_SCOPED.contains(type))
            .filter(type -> !TenantScoped.class.isAssignableFrom(type))
            .map(Class::getSimpleName)
            .toList();

        assertThat(unscoped)
            .as("entities missing TenantScoped — add tenant_id and a policy, "
                + "or add the class to NOT_TENANT_SCOPED with a reason")
            .isEmpty();
    }

    @Test
    @DisplayName("every tenant-scoped entity has the listener that populates the column")
    void everyTenantScopedEntityHasTheAssigningListener() {
        List<String> missingListener = allEntities().stream()
            .filter(TenantScoped.class::isAssignableFrom)
            .filter(type -> {
                EntityListeners listeners = type.getAnnotation(EntityListeners.class);
                return listeners == null
                    || List.of(listeners.value()).stream()
                        .noneMatch(TenantAssigningListener.class::equals);
            })
            .map(Class::getSimpleName)
            .toList();

        assertThat(missingListener)
            .as("TenantScoped without @EntityListeners(TenantAssigningListener.class): "
                + "every insert would be rejected by the policy's WITH CHECK")
            .isEmpty();
    }

    @Test
    @DisplayName("every tenant-scoped table has RLS enabled, forced, and a policy")
    void everyTenantScopedTableIsProtectedInTheDatabase() {
        List<String> unprotected = allEntities().stream()
            .filter(TenantScoped.class::isAssignableFrom)
            .map(TenantIsolationCoverageTest::tableNameOf)
            .filter(table -> !isFullyProtected(table))
            .toList();

        assertThat(unprotected)
            .as("tables without RLS enabled + forced + a policy — a migration is missing")
            .isEmpty();
    }

    private boolean isFullyProtected(String table) {
        Boolean protectedTable = jdbcTemplate.queryForObject("""
            SELECT c.relrowsecurity
               AND c.relforcerowsecurity
               AND EXISTS (SELECT 1 FROM pg_policies p
                            WHERE p.schemaname = 'public' AND p.tablename = c.relname)
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relname = ?
            """, Boolean.class, table);
        return Boolean.TRUE.equals(protectedTable);
    }

    @Test
    @DisplayName("the role the application runs as cannot bypass the policies")
    void applicationRoleIsNeitherSuperuserNorBypassRls() {
        // This is the assertion that would have caught the original hole: every
        // policy was present and correct, and a superuser connection read every
        // tenant's rows anyway, silently.
        var role = jdbcTemplate.queryForMap(
            "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");

        assertThat(role.get("rolsuper")).as("application role must not be a superuser").isEqualTo(false);
        assertThat(role.get("rolbypassrls")).as("application role must not hold BYPASSRLS").isEqualTo(false);
    }

    private static String tableNameOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        // Hibernate's implicit naming: CamelCase becomes snake_case.
        return entity.getSimpleName()
            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .toLowerCase();
    }
}
