package com.cde.platform.support;

import com.cde.platform.repository.TenantRepository;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContextBinder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;

/**
 * Binds the default tenant around every integration test.
 *
 * <p>Tests are background work in the sense that matters here: nothing has
 * authenticated, so nothing has established tenant context, and under
 * Row-Level Security that means every read returns no rows and every write is
 * rejected by the policy's {@code WITH CHECK}. Requiring each test to remember
 * would be a per-test detail with a badly misleading failure mode — a test that
 * forgot would not error, it would quietly assert against an empty result.
 *
 * <p>Bound in {@code beforeAll} as well as {@code beforeEach} because fixture
 * setup in a {@code @BeforeAll} runs before any per-test callback, and that is
 * exactly where integration tests insert the rows they go on to read.
 *
 * <p>Applies only to classes annotated {@link SpringBootTest}. A pure unit test
 * has no context to look a tenant up in, and should not have one.
 */
public class DefaultTenantExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        bindDefaultTenant(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        bindDefaultTenant(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        // Not cleared per-test: JUnit may run @AfterEach and @AfterAll fixture
        // teardown that still needs to reach the database.
        TenantContextBinder.clear();
    }

    private void bindDefaultTenant(ExtensionContext context) {
        if (!isSpringBootTest(context)) {
            return;
        }
        springContext(context).ifPresent(applicationContext -> {
            if (applicationContext.getBeanNamesForType(TenantRepository.class).length == 0) {
                return;
            }
            var tenancyProperties = applicationContext.getBean(TenancyProperties.class);
            applicationContext.getBean(TenantRepository.class)
                .findBySlug(tenancyProperties.getDefaultTenantSlug())
                .ifPresent(tenant -> TenantContextBinder.bind(tenant.getId()));
        });
    }

    private boolean isSpringBootTest(ExtensionContext context) {
        return context.getTestClass()
            .map(testClass -> testClass.isAnnotationPresent(SpringBootTest.class))
            .orElse(false);
    }

    private Optional<ApplicationContext> springContext(ExtensionContext context) {
        try {
            return Optional.ofNullable(SpringExtension.getApplicationContext(context));
        } catch (Exception e) {
            // The context failed to start. That is the test's own failure to
            // report; masking it with one from here would hide the cause.
            return Optional.empty();
        }
    }
}
