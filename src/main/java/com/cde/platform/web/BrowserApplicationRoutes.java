package com.cde.platform.web;

/**
 * The client-side routes the browser application owns.
 *
 * <p>Two places need this list and neither can derive it from the other: the
 * security configuration, to decide which requests get the document policy,
 * and {@link BrowserApplicationController}, whose {@code @GetMapping} needs
 * literal strings because an annotation takes only compile-time constants.
 *
 * <p>So it is written twice, and {@code BrowserApplicationRoutesTest} asserts
 * the two copies agree. The failure that test exists for is quiet: a route
 * added to the controller and not here still serves its document, with the
 * API's {@code default-src 'none'} policy over it, and the application renders
 * as a blank page.
 */
public final class BrowserApplicationRoutes {

    private BrowserApplicationRoutes() {
    }

    /** The embed route, which is the only one a host may frame (ADR 14). */
    public static final String EMBED = "/embed";

    /**
     * Every route, as path patterns.
     *
     * <p>Deliberately not a wildcard. A catch-all would also answer paths the
     * API is supposed to refuse, turning a 404 problem document into an HTML
     * page and making a misspelled endpoint look like it exists.
     */
    public static final String[] ALL = {
        "/", "/login", "/projects",
        EMBED,
        "/viewer/{id}", "/viewer3d/{id}",
        "/compare", "/visual-compare",
    };
}
