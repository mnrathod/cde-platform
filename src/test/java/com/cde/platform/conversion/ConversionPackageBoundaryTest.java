package com.cde.platform.conversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the conversion service still depends on this platform.
 *
 * <p>ADR 12 ships the conversion service as something a customer installs
 * beside the viewer, where there is no tenant table, no user table and no
 * Row-Level Security. The work of getting there is not moving files — it is
 * making sure that when they are moved, nothing comes with them. Every import
 * of {@code tenancy} or {@code model.Tenant} is a file that would have to
 * travel too, and each one is invisible in a build that compiles perfectly
 * well with the dependency pointing the wrong way.
 *
 * <p>So it is asserted rather than intended, in the same shape as the
 * frontend's {@code viewer-core.boundary.spec.ts} and for the same reason.
 * Reading the source rather than the compiled classes is deliberate: it needs
 * no bytecode-analysis dependency (§0.3), and an import is exactly the thing
 * being ruled out.
 *
 * <p>The allow-list is small on purpose. It is not "packages we happen to use";
 * it is the set that would be carried into the extracted service, and every
 * entry has to be defensible as belonging there.
 */
@DisplayName("conversion package boundary")
class ConversionPackageBoundaryTest {

    private static final Path CONVERSION = Path.of("src/main/java/com/cde/platform/conversion");

    /**
     * Packages the conversion service may depend on.
     *
     * <p>{@code model}, {@code repository} and {@code service} appear because
     * {@code ConversionJob}, {@code ConversionJobRepository} and
     * {@code ConverterService} are the conversion service's own code, sitting
     * in this platform's shared packages for historical reasons. They move with
     * it. That is a different thing from depending on the platform, and the
     * per-class assertion below is what keeps the distinction honest — without
     * it, this entry would quietly permit any entity at all.
     */
    private static final Set<String> PERMITTED_PACKAGES =
        Set.of("conversion", "fetch", "storage", "upload", "openapi", "exception",
               "model", "repository", "service");

    /**
     * The only classes the conversion service may use from a shared package.
     */
    private static final Set<String> PERMITTED_SHARED_CLASSES =
        Set.of("model.ConversionJob", "repository.ConversionJobRepository",
               "service.ConverterService", "exception.ResourceNotFoundException");

    private static final Pattern PLATFORM_IMPORT =
        Pattern.compile("^import\\s+(?:static\\s+)?com\\.cde\\.platform\\.([a-z]+)\\.([A-Za-z0-9_.]+);",
                        Pattern.MULTILINE);

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(CONVERSION)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("has sources to check, so a moved directory cannot pass silently")
    void hasSourcesToCheck() throws IOException {
        // Without this, every assertion below is a vacuous truth over an empty
        // set the moment the package is renamed or moved.
        assertThat(sources()).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("knows nothing about tenants, users or row-level security")
    void doesNotDependOnPlatformTenancy() throws IOException {
        for (Path source : sources()) {
            String body = Files.readString(source);
            assertThat(body)
                .as("%s must reach tenancy through ConversionCallers, not directly. "
                  + "A caller is whoever authenticated; a tenant is this platform's "
                  + "answer to that, and the extracted service will have a different one.",
                    source.getFileName())
                .doesNotContain("TenantContext")
                .doesNotContain("TenantRepository")
                .doesNotContain("com.cde.platform.model.Tenant;")
                .doesNotContain("UserRepository");
        }
    }

    @Test
    @DisplayName("imports nothing from a package that would not travel with it")
    void importsOnlyPermittedPackages() throws IOException {
        for (Path source : sources()) {
            Matcher m = PLATFORM_IMPORT.matcher(Files.readString(source));
            while (m.find()) {
                assertThat(PERMITTED_PACKAGES)
                    .as("%s imports com.cde.platform.%s, which is not part of the "
                      + "conversion service and would have to be carried into the "
                      + "extracted deployment", source.getFileName(), m.group(1))
                    .contains(m.group(1));
            }
        }
    }

    @Test
    @DisplayName("uses only its own classes from the shared packages")
    void usesOnlyItsOwnSharedClasses() throws IOException {
        for (Path source : sources()) {
            Matcher m = PLATFORM_IMPORT.matcher(Files.readString(source));
            while (m.find()) {
                String pkg = m.group(1);
                if (!Set.of("model", "repository", "service").contains(pkg)) continue;

                // Nested types are imported as Outer.Inner; the outer class is
                // what decides whether this is the conversion service's own code.
                String outer = m.group(2).split("\\.")[0];
                assertThat(PERMITTED_SHARED_CLASSES)
                    .as("%s imports %s.%s. The shared packages are permitted only for "
                      + "the conversion service's own entity, repository and sidecar "
                      + "client — anything else is platform code arriving through a "
                      + "door left open for them.", source.getFileName(), pkg, outer)
                    .contains(pkg + "." + outer);
            }
        }
    }
}
