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
 * Whether the conversion service still depends on the platform that hosts it.
 *
 * <p>ADR 12 ships this as something a customer installs beside the viewer,
 * where there is no tenant table, no user table and no Row-Level Security. It
 * is now a Gradle module with no dependency on the application, so the imports
 * this used to forbid are ones the compiler cannot resolve — reaching for
 * {@code TenantContext} from here does not fail a test, it fails to build.
 *
 * <p>What that does not stop is the module growing the platform back inside
 * itself: a {@code model.Document} added here compiles perfectly well, and so
 * does a second {@code TenantContext} of its own. That is what these
 * assertions are for now, and it is why the allow-list is a list of *classes*
 * rather than packages — without it, {@code model} would quietly permit any
 * entity at all.
 *
 * <p>Reading the source rather than the compiled classes is deliberate: it
 * needs no bytecode-analysis dependency (§0.3), and it catches the reach
 * whether it arrives as an import, a comment, or a string handed to
 * reflection.
 */
@DisplayName("conversion service boundary")
class ConversionPackageBoundaryTest {

    /**
     * This module's own source root — everything that would travel.
     *
     * <p>Relative to the module directory, which is where Gradle runs the test
     * from. Scoping it to the whole module rather than one package is what
     * makes {@code storage}, {@code upload} and {@code fetch} subject to the
     * same rule as {@code conversion}: they travel too.
     */
    private static final Path SOURCES = Path.of("src/main/java/com/cde/platform");

    /**
     * Packages the conversion service may depend on.
     *
     * <p>{@code openapi} and {@code exception} come from {@code
     * api-conventions}, which travels as a jar. {@code model}, {@code
     * repository} and {@code service} are this module's own code sitting in
     * the platform's shared package names for historical reasons — a different
     * thing from depending on the platform, and the per-class assertion below
     * is what keeps the distinction honest.
     */
    private static final Set<String> PERMITTED_PACKAGES =
        Set.of("conversion", "fetch", "storage", "upload", "openapi", "exception",
               "model", "repository", "service");

    /**
     * The only classes the conversion service may use from a shared package.
     *
     * <p>{@code exception} is on this list as well as in
     * {@link #PERMITTED_PACKAGES} because the package split across two modules:
     * three of its classes moved to {@code api-conventions} and the rest —
     * {@code GlobalExceptionHandler}, {@code ApiProblem} — stayed with the
     * application. Permitting the package alone would have said nothing about
     * which half.
     */
    private static final Set<String> PERMITTED_SHARED_CLASSES =
        Set.of("model.ConversionJob", "repository.ConversionJobRepository",
               "service.ConverterService", "service.DxfToSvgService",
               "exception.ResourceNotFoundException",
               "exception.ConverterOfflineException",
               "exception.ResourceConflictException");

    /**
     * Any reference to a platform type, anywhere in the file — not just the
     * import block.
     *
     * <p>This was anchored to {@code ^import}. {@code ConversionJobController}
     * wrote {@code new com.cde.platform.exception.ResourceConflictException(…)}
     * inline, with no import to find, and the assertion below had nothing to
     * look at. A rule that only reads the import block tests a coding style,
     * not a dependency.
     *
     * <p>It matches prose in Javadoc too. That is deliberate: a comment naming
     * a forbidden package is either a stale reference or a plan, and both are
     * worth a sentence in the diff.
     */
    private static final Pattern PLATFORM_REFERENCE =
        Pattern.compile("com\\.cde\\.platform\\.([a-z]+)\\.([A-Z][A-Za-z0-9_]*)");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("has sources to check, so a moved directory cannot pass silently")
    void hasSourcesToCheck() throws IOException {
        // Without this, every assertion below is a vacuous truth over an empty
        // set the moment the package is renamed or moved.
        assertThat(sources()).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("knows nothing about tenants, users or row-level security")
    void doesNotDependOnPlatformTenancy() throws IOException {
        for (Path source : sources()) {
            String body = Files.readString(source);
            assertThat(body)
                .as("%s must reach tenancy through ConversionCallers, not directly. "
                  + "A caller is whoever authenticated; a tenant is this platform's "
                  + "answer to that, and a host that installs this beside the viewer "
                  + "will have a different one.", source.getFileName())
                .doesNotContain("TenantContext")
                .doesNotContain("TenantRepository")
                .doesNotContain("com.cde.platform.model.Tenant;")
                .doesNotContain("UserRepository")
                // Added after the module split: ConversionJob implemented
                // TenantScoped and carried TenantAssigningListener, and every
                // assertion in this class passed anyway. Naming four types was
                // never the rule — "nothing from tenancy" is — and the two that
                // slipped through are the two that were not named.
                .doesNotContain("TenantScoped")
                .doesNotContain("TenantAssigningListener");
        }
    }

    @Test
    @DisplayName("names nothing from a package that would not travel with it")
    void referencesOnlyPermittedPackages() throws IOException {
        for (Path source : sources()) {
            Matcher m = PLATFORM_REFERENCE.matcher(Files.readString(source));
            while (m.find()) {
                assertThat(PERMITTED_PACKAGES)
                    .as("%s names com.cde.platform.%s, which is not part of the "
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
            Matcher m = PLATFORM_REFERENCE.matcher(Files.readString(source));
            while (m.find()) {
                String pkg = m.group(1);
                if (!Set.of("model", "repository", "service", "exception").contains(pkg)) continue;

                String outer = m.group(2);
                assertThat(PERMITTED_SHARED_CLASSES)
                    .as("%s names %s.%s. The shared packages are permitted only for "
                      + "the conversion service's own entity, repository and sidecar "
                      + "client — anything else is platform code arriving through a "
                      + "door left open for them.", source.getFileName(), pkg, outer)
                    .contains(pkg + "." + outer);
            }
        }
    }
}
