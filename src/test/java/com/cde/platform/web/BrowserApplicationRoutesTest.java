package com.cde.platform.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route list is written twice, so this asserts the two copies agree.
 *
 * <p>{@link BrowserApplicationRoutes#ALL} decides which requests get the
 * document content-security policy; the controller's {@code @GetMapping}
 * decides which ones get a document. An annotation takes only compile-time
 * constants, so the second cannot be derived from the first.
 *
 * <p>The failure is quiet in the direction that matters. A route added to the
 * controller and forgotten here still serves its document — with the API's
 * {@code default-src 'none'} over it, which refuses the application's own
 * scripts and renders a blank page. Nothing logs, nothing 500s, and it looks
 * like a broken build.
 *
 * <p>No Spring context: the annotation is read by reflection, so this runs
 * wherever a database container does not.
 */
class BrowserApplicationRoutesTest {

    private static Set<String> mappedRoutes() throws NoSuchMethodException {
        GetMapping mapping = BrowserApplicationController.class
            .getMethod("document", HttpServletRequest.class)
            .getAnnotation(GetMapping.class);
        assertThat(mapping)
            .as("the controller method that serves the document is still mapped with @GetMapping")
            .isNotNull();
        return Set.of(mapping.value());
    }

    @Test
    @DisplayName("every route the controller serves gets the document policy")
    void controllerRoutesAreAllCoveredByThePolicy() throws Exception {
        assertThat(mappedRoutes())
            .as("routes served but not covered — these would render as a blank page")
            .isSubsetOf(BrowserApplicationRoutes.ALL);
    }

    @Test
    @DisplayName("the policy covers no route the controller does not serve")
    void policyCoversNothingExtra() throws Exception {
        // The other direction is less dangerous — a 404 with a document policy
        // over it harms nothing — but a stale entry here is a claim that a route
        // exists, and the next person to read this list will believe it.
        assertThat(List.of(BrowserApplicationRoutes.ALL))
            .as("routes claimed by the policy that nothing serves")
            .allMatch(route -> mappedRoutesQuietly().contains(route));
    }

    @Test
    @DisplayName("the embed route is one of them, and is spelled the same way")
    void embedRouteIsInTheList() {
        // EMBED is used on its own to decide framing. If it ever stopped
        // matching an entry in ALL, the embed document would be served with
        // frame-ancestors 'none' and the iframe would be refused with the
        // allow-list correctly configured — the worst kind of support call.
        assertThat(BrowserApplicationRoutes.ALL).contains(BrowserApplicationRoutes.EMBED);
    }

    private static Set<String> mappedRoutesQuietly() {
        try {
            return mappedRoutes();
        } catch (NoSuchMethodException absent) {
            throw new AssertionError("the document method is gone", absent);
        }
    }
}
