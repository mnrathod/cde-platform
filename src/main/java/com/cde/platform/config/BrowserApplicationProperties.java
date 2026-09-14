package com.cde.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the built browser application lives, when this image serves it.
 *
 * <p>ADR 15: the viewer is a product a customer installs, and an install that
 * needs a second artefact and a web tier configured to match is an install that
 * goes wrong in a way support cannot see. Pointing this at a build makes the
 * image self-contained — one thing to run, one place the embed document and its
 * {@code frame-ancestors} header come from.
 *
 * <p><strong>Empty by default, and empty means this image serves no
 * application at all</strong> — exactly what it did before this setting
 * existed. A deployment that puts the build behind its own web tier leaves it
 * unset and is unaffected.
 */
@ConfigurationProperties(prefix = "cde.web.app")
@Validated
public class BrowserApplicationProperties {

    /**
     * Directory holding the built browser bundle — the one containing
     * {@code index.html}, not the directory above it.
     *
     * <p>For the Angular application builder that is {@code dist/cde-web/browser}.
     * The container image stages it at {@code /app/web}.
     */
    private String path = "";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path == null ? "" : path.trim();
    }

    /** @return true when a deployment has asked this image to serve the application. */
    public boolean isConfigured() {
        return !path.isEmpty();
    }

    /** The configured directory. Only meaningful when {@link #isConfigured()}. */
    public Path directory() {
        return Path.of(path);
    }

    /** The document every client-side route resolves to. */
    public Path indexDocument() {
        return directory().resolve("index.html");
    }

    /**
     * Fail at startup when the directory is named but not there.
     *
     * <p>Without this the application boots, serves its API perfectly, and
     * answers every page request with a 404 — which reads as a routing problem
     * and is a mounting one. §13 asks for loud failure over a half-configured
     * boot, and this is a case where the half is invisible from the outside.
     *
     * @throws IllegalStateException naming the path that was not found
     */
    public void requireApplicationPresentWhenConfigured() {
        if (!isConfigured()) {
            return;
        }
        if (!Files.isDirectory(directory())) {
            throw new IllegalStateException(
                "cde.web.app.path is set to \"" + path + "\" but that is not a directory. "
                + "Point it at the built browser bundle — the directory containing "
                + "index.html — or leave it empty to serve no application from this image.");
        }
        if (!Files.isRegularFile(indexDocument())) {
            throw new IllegalStateException(
                "cde.web.app.path is set to \"" + path + "\" but there is no index.html in it. "
                + "In a container this usually means the image was built without staging a "
                + "bundle: run scripts/stage-browser-app.sh before building it, or set "
                + "CDE_WEB_APP_PATH to empty to run the API alone. Outside a container, the "
                + "bundle is the directory containing index.html — for the Angular application "
                + "builder that is dist/cde-web/browser, one level below the output path.");
        }
    }
}
