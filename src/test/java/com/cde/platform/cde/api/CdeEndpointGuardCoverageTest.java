package com.cde.platform.cde.api;

import com.cde.platform.cde.domain.ContainerPermission;
import com.cde.platform.cde.service.ContainerLifecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deny by default, asserted over the <em>set</em> of endpoints rather than any
 * particular one.
 *
 * <p>The companion test proves that the permissions behave correctly on the
 * endpoints it exercises. This proves there are no endpoints it forgot: the
 * realistic failure is not that someone disables a check, it is that somebody
 * adds a twelfth endpoint next year and nobody notices it carries no permission
 * requirement at all. That gap has to appear as a build failure naming the new
 * method, not as a line missing from a test somebody would have had to
 * remember to write.
 *
 * <p>No Spring context: this reads annotations, so it needs the classes and
 * nothing else.
 */
class CdeEndpointGuardCoverageTest {

    private static final String CDE_PACKAGE = "com.cde.platform.cde";

    /** The request-mapping annotations that make a method an endpoint. */
    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
        RequestMapping.class, GetMapping.class, PostMapping.class,
        PutMapping.class, PatchMapping.class, DeleteMapping.class);

    private static final Pattern AUTHORITY = Pattern.compile("'([a-z]+:[a-z]+)'");

    /** Every lifecycle operation, named so that adding one without a check fails here. */
    private static final List<String> LIFECYCLE_OPERATIONS = List.of(
        "startWorkInProgress", "share", "reject", "publish",
        "supersede", "archive", "assignSuitabilityCode");

    @Test
    @DisplayName("every CDE endpoint declares a permission")
    void noEndpointIsUnguarded() {
        List<String> unguarded = new ArrayList<>();

        for (Class<?> controller : componentsAnnotatedWith(RestController.class)) {
            boolean guardedAtClassLevel = controller.isAnnotationPresent(PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (isEndpoint(method)
                    && !guardedAtClassLevel
                    && !method.isAnnotationPresent(PreAuthorize.class)) {
                    unguarded.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertThat(unguarded)
            .as("CDE endpoints with no permission requirement — the default must be deny")
            .isEmpty();
    }

    @Test
    @DisplayName("the scan finds the controllers at all")
    void controllerScanIsNotVacuous() {
        // Without this, a scan that matched nothing would make the assertion
        // above pass over an empty list — green, and covering nothing.
        assertThat(componentsAnnotatedWith(RestController.class))
            .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("every authority named in an expression is a permission that exists")
    void noExpressionNamesAnUnknownAuthority() {
        Set<String> known = ContainerPermission.ALL;
        List<String> unknown = new ArrayList<>();

        for (Class<?> type : allCdeComponents()) {
            for (String expression : preAuthorizeExpressionsOn(type)) {
                Matcher matcher = AUTHORITY.matcher(expression);
                while (matcher.find()) {
                    if (!known.contains(matcher.group(1))) {
                        unknown.add(type.getSimpleName() + ": " + matcher.group(1));
                    }
                }
            }
        }

        // A misspelled authority fails closed — nobody holds it, so the
        // endpoint refuses everyone. Safe, but it presents as a permissions
        // bug with no visible cause, so it is named here instead.
        assertThat(unknown).as("authorities named in @PreAuthorize but never granted").isEmpty();
    }

    @Test
    @DisplayName("every lifecycle operation on the service is guarded")
    void serviceOperationsAreGuarded() {
        // The controllers state each endpoint's requirement, but the service is
        // where it has to hold: a scheduled job or a second controller would
        // not come through the first layer.
        List<String> unguarded = LIFECYCLE_OPERATIONS.stream()
            .filter(name -> !hasGuardedMethodNamed(ContainerLifecycleService.class, name))
            .toList();

        assertThat(unguarded).as("lifecycle operations with no permission check").isEmpty();
    }

    @Test
    @DisplayName("the operations this test names still exist on the service")
    void theNamedOperationsAreReal() {
        // Renaming an operation would otherwise quietly empty the list above:
        // a method that does not exist cannot be found unguarded.
        List<String> missing = LIFECYCLE_OPERATIONS.stream()
            .filter(name -> java.util.Arrays.stream(ContainerLifecycleService.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals(name)))
            .toList();

        assertThat(missing)
            .as("operations named here that no longer exist — rename them in this test too")
            .isEmpty();
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private boolean hasGuardedMethodNamed(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.isAnnotationPresent(PreAuthorize.class)) {
                return true;
            }
        }
        return false;
    }

    private List<String> preAuthorizeExpressionsOn(Class<?> type) {
        List<String> expressions = new ArrayList<>();
        if (type.isAnnotationPresent(PreAuthorize.class)) {
            expressions.add(type.getAnnotation(PreAuthorize.class).value());
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PreAuthorize.class)) {
                expressions.add(method.getAnnotation(PreAuthorize.class).value());
            }
        }
        return expressions;
    }

    private boolean isEndpoint(Method method) {
        return MAPPINGS.stream().anyMatch(method::isAnnotationPresent);
    }

    private List<Class<?>> allCdeComponents() {
        List<Class<?>> types = new ArrayList<>(componentsAnnotatedWith(RestController.class));
        types.addAll(componentsAnnotatedWith(Service.class));
        return types;
    }

    private List<Class<?>> componentsAnnotatedWith(Class<? extends Annotation> annotation) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));

        List<Class<?>> types = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents(CDE_PACKAGE)) {
            try {
                types.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return types;
    }
}
