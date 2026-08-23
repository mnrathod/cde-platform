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
        // Only the outermost class clears. A @Nested class finishing must not
        // unbind the tenant its siblings still need — and it must not do so
        // before the enclosing class's own @AfterAll teardown, which still
        // reaches the database.
        //
        // Not cleared per-test either, for the same reason: @AfterEach
        // teardown runs after the callback would have fired.
        if (context.getTestClass().map(type -> type.getEnclosingClass() == null).orElse(true)) {
            TenantContextBinder.clear();
        }
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

    /**
     * Walks out through enclosing classes, because {@code @Nested} classes do
     * not carry the annotation themselves — it sits on the outer test class.
     * Checking only the immediate class silently skipped every nested test,
     * and the first nested class's afterAll then cleared the binding for all
     * the ones after it.
     */
    private boolean isSpringBootTest(ExtensionContext context) {
        for (Class<?> testClass = context.getTestClass().orElse(null);
             testClass != null;
             testClass = testClass.getEnclosingClass()) {
            if (testClass.isAnnotationPresent(SpringBootTest.class)) {
                return true;
            }
        }
        return false;
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
