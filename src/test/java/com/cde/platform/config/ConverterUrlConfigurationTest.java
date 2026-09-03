package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the address of the conversion service comes from configuration.
 *
 * <p>It is set by {@code cde.converter.url}, and {@code docker-compose.yml}
 * points it at {@code http://converter:5001} because the converter is a
 * separate service. Two controllers ignored that and used a compiled-in
 * {@code http://localhost:5001}, which inside the application container
 * resolves to the application itself — so 3D, IFC and the model-tree paths
 * could not reach the converter in any deployment where the two are not on
 * one host, while working perfectly on a developer's machine, where they are.
 *
 * <p>That is the shape of the bug this guards against: it passes every test
 * run on a single host and fails only once deployed. A grep is a blunt
 * instrument, but the property it checks is exact — no source file should
 * contain the converter's address as a literal, because there is a setting
 * for it (§13).
 */
class ConverterUrlConfigurationTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /**
     * The literal that must not appear. Written in pieces so this file does
     * not match its own rule when the check is run over the test tree too.
     */
    private static final String FORBIDDEN_LITERAL = "http://" + "localhost:5001";

    /**
     * {@code application.yml} is where the default belongs, and
     * {@code @Value("${cde.converter.url:...}")} declarations carry it as a
     * fallback for a developer running everything locally. Those are the
     * property being defined, not a bypass of it.
     */
    private static final String PROPERTY_REFERENCE = "${cde.converter.url";

    @Test
    @DisplayName("no source file reaches the converter at a compiled-in address")
    void takesTheConverterAddressFromConfiguration() throws IOException {
        assertThat(MAIN_SOURCES)
            .describedAs("main sources, resolved from the Gradle working directory")
            .exists();

        List<String> offenders;
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            offenders = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(ConverterUrlConfigurationTest::hardcodesTheConverterAddress)
                .map(MAIN_SOURCES::relativize)
                .map(Path::toString)
                .sorted()
                .toList();
        }

        assertThat(offenders)
            .describedAs("files using %s outside a @Value default — inject "
                       + "cde.converter.url instead", FORBIDDEN_LITERAL)
            .isEmpty();
    }

    private static boolean hardcodesTheConverterAddress(Path source) {
        try {
            return Files.readAllLines(source).stream()
                .filter(line -> line.contains(FORBIDDEN_LITERAL))
                .filter(line -> !isComment(line))
                // A line that names the property is declaring its default,
                // which is the mechanism working rather than being avoided.
                .anyMatch(line -> !line.contains(PROPERTY_REFERENCE));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + source, e);
        }
    }

    /**
     * Comments are exempt, because the fix for this defect explains itself by
     * quoting the address it removed — and a check that forbids describing a
     * bug is a check people work around by deleting the explanation.
     */
    private static boolean isComment(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }
}
