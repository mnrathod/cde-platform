package com.cde.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Serves the browser application's scripts, styles and assets from disk.
 *
 * <p>Everything here is a fingerprinted file the build produced. The entry
 * document is deliberately <em>not</em> served this way — it carries a
 * per-request nonce, so it goes through {@code BrowserApplicationController}
 * instead.
 */
@Configuration
public class BrowserApplicationResourceConfig implements WebMvcConfigurer {

    private final BrowserApplicationProperties properties;

    public BrowserApplicationResourceConfig(BrowserApplicationProperties properties) {
        this.properties = properties;
        properties.requireApplicationPresentWhenConfigured();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!properties.isConfigured()) {
            return;
        }
        // A trailing separator is required: without it Spring treats the value
        // as a file prefix rather than a directory, and "/app/web" + "main.js"
        // resolves to "/app/webmain.js".
        String location = properties.directory().toUri().toString();

        registry.addResourceHandler(
                "/*.js", "/*.css", "/*.ico", "/*.webmanifest", "/*.txt",
                "/assets/**", "/icons/**", "/media/**")
            .addResourceLocations(location)
            // The build fingerprints these names, so a changed file is a
            // changed URL and a year is safe. index.html is no-store and is
            // what points at the current set (§7.2).
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
