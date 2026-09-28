package com.cde.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint states the permission it requires.
 *
 * <p>§5.5's rule is "deny by default", and its own note says a missing
 * annotation should fail a test. There was no such test, and the result was
 * not a near miss: <em>sixty-two</em> endpoints — the entire document surface —
 * carried no permission check at all. The only gate was {@code
 * .anyRequest().authenticated()}, so the requirement each endpoint published in
 * its own description ("Requires the {@code document:write} permission") was
 * enforced against nobody. A viewer could delete documents, burn redactions
 * into them, rearrange their pages, revoke signatures and delete projects.
 *
 * <p>The check is reflective rather than behavioural on purpose. A test per
 * endpoint per role would be hundreds of cases and would still only cover the
 * endpoints someone remembered to write a case for — whereas the failure being
 * guarded against is precisely the one nobody remembered. This asks the
 * classpath what endpoints exist, so an endpoint added next year is in scope
 * the moment it compiles.
 *
 * <p>It deliberately does not verify that the annotation names the
 * <em>right</em> permission; no reflective check can know that. What it
 * guarantees is that a decision was taken and written down, and
 * {@link DocumentSurfaceAuthorisationTest} covers the consequence — that a
 * read-only role cannot reach a writing endpoint.
 *
 * <p>This check is not a new idea here: {@code CdeEndpointGuardCoverageTest}
 * has done the same thing since the CDE module was built. It was scoped to
 * {@code com.cde.platform.cde}, which is why it never saw the sixty-two
 * unguarded endpoints in the packages beside it — the same failure
 * {@code TenantIsolationCoverageTest} records having had, where a scan aimed
 * at the package that happened to exist reported full coverage of a set it was
 * not looking at. This one scans {@code com.cde.platform}. The CDE test keeps
 * its own cases, which go further than coverage: they assert that the service
 * layer is guarded too, so a second controller or a scheduled job could not
 * reach a lifecycle operation around the first layer.
 */
class EndpointPermissionCoverageTest {

    private static final String CONTROLLERS = "com.cde.platform";

    /**
     * The endpoints that require no permission, each because of something
     * specific.
     *
     * <p>An allow-list rather than a rule, because every entry is a judgement
     * that deserves to be read and argued with. Adding to it is the point at
     * which someone has to justify an unguarded endpoint, which is the review
     * this file exists to force.
     */
    private static final Set<String> NO_PERMISSION_REQUIRED = Set.of(
        // Reached before the caller has any permissions to check.
        "AuthController#login",
        "RegistrationController#register",

        // Documented as requiring only a valid session: they report on, and end,
        // the caller's own session. There is no permission to hold — the
        // principal is the resource.
        "AuthController#session",
        "AuthController#logout",

        // Deliberately open, and reasoned about in SecurityConfig: a fault
        // worth reporting often happens once the session has already failed,
        // so requiring a credential would lose exactly the reports worth
        // having. The input is bounded and stripped before it reaches a log.
        "ErrorLogController#logError",

        // Serves the browser application's own HTML shell, which carries no
        // data. The application authenticates against /api like any other
        // client and every API request is authorised on its own.
        "BrowserApplicationController#document"
    );

    @Test
    @DisplayName("every endpoint carries an explicit permission")
    void everyEndpointDeclaresAPermission() {
        List<String> unguarded = new ArrayList<>();

        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(RequestMapping.class)
                    && !hasMappingAnnotation(method)) {
                    continue;
                }
                String name = controller.getSimpleName() + "#" + method.getName();
                if (NO_PERMISSION_REQUIRED.contains(name)) continue;
                if (method.isAnnotationPresent(PreAuthorize.class)) continue;
                if (controller.isAnnotationPresent(PreAuthorize.class)) continue;
                unguarded.add(name);
            }
        }

        assertThat(new TreeSet<>(unguarded))
            .as("endpoints with no @PreAuthorize and no entry in NO_PERMISSION_REQUIRED — "
                + "either annotate them or justify the exception there")
            .isEmpty();
    }

    @Test
    @DisplayName("the exception list names only endpoints that still exist")
    void allowListDoesNotOutliveItsEndpoints() {
        // An entry for a deleted or renamed endpoint reads as a reviewed
        // exception while excusing nothing, and would silently excuse a new
        // endpoint that happened to take the same name.
        Set<String> existing = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(RequestMapping.class)
                    || hasMappingAnnotation(method)) {
                    existing.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(existing).containsAll(NO_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("every permission an endpoint names is one that exists")
    void everyNamedPermissionIsRealVocabulary() {
        // A misspelled authority fails closed: nobody holds it, so the endpoint
        // refuses everyone. That is safe, but it presents as a permissions bug
        // with no cause visible at the call site, and it can sit unnoticed on
        // an endpoint nobody exercises.
        Set<String> vocabulary = new TreeSet<>();
        vocabulary.addAll(com.cde.platform.cde.domain.ContainerPermission.ALL);
        vocabulary.addAll(TenantPermission.ALL);
        vocabulary.addAll(ConversionPermission.ALL);
        vocabulary.addAll(DocumentPermission.ALL);
        vocabulary.addAll(MarkupPermission.ALL);
        vocabulary.addAll(ProjectPermission.ALL);
        vocabulary.addAll(SignaturePermission.ALL);

        List<String> unknown = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
                if (guard == null) continue;
                for (String authority : authoritiesNamedIn(guard.value())) {
                    if (!vocabulary.contains(authority)) {
                        unknown.add(controller.getSimpleName() + "#" + method.getName()
                                    + " names '" + authority + "'");
                    }
                }
            }
        }

        assertThat(unknown).isEmpty();
    }

    /** The authority strings inside a {@code hasAuthority('…')} expression. */
    private static List<String> authoritiesNamedIn(String expression) {
        List<String> found = new ArrayList<>();
        var matcher = java.util.regex.Pattern
            .compile("hasAuthority\\('([^']+)'\\)").matcher(expression);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    private static boolean hasMappingAnnotation(Method method) {
        return java.util.Arrays.stream(method.getAnnotations())
            .anyMatch(annotation -> annotation.annotationType()
                .isAnnotationPresent(RequestMapping.class));
    }

    /**
     * Scans the classpath rather than taking a written list, because a written
     * list is one more thing to forget to add to.
     */
    private static List<Class<?>> controllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLERS)) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new AssertionError("Scanned a class that will not load", e);
            }
        }
        // A scan that silently matched nothing would pass every assertion here.
        assertThat(found).as("controllers found on the classpath").isNotEmpty();
        return found;
    }
}
