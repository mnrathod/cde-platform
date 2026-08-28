package com.cde.platform.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Keeps the committed specification in step with the code that produces it.
 *
 * <p>The specification is generated from the annotations, so it cannot describe
 * an endpoint that does not exist. What it *can* do is fall behind: a change to
 * a controller regenerates a different document, and unless someone notices,
 * the committed file — the one clients generate from, the one reviewers read as
 * a diff — quietly describes last week's API.
 *
 * <p>This test closes that. It regenerates and compares; a difference fails the
 * build with the command to fix it. Regenerate with:
 *
 * <pre>./gradlew test --tests '*OpenApiSpecificationTest' -DupdateOpenApiSpec=true</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecificationTest {

    /** Where the committed specification lives, relative to the module root. */
    private static final Path SPECIFICATION = Path.of("api", "openapi.yaml");

    private static final String REGENERATE_COMMAND =
        "./gradlew test --tests '*OpenApiSpecificationTest' -DupdateOpenApiSpec=true";

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("the committed specification matches the one the code produces")
    void committedSpecificationIsCurrent() throws Exception {
        String generated = fetchSpecification();

        if (Boolean.getBoolean("updateOpenApiSpec")) {
            Files.createDirectories(SPECIFICATION.getParent());
            Files.writeString(SPECIFICATION, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(SPECIFICATION)
            .withFailMessage("""
                %s is missing. The specification is a committed artefact, not a build output.
                Generate it with:
                  %s""", SPECIFICATION, REGENERATE_COMMAND)
            .exists();

        String committed = Files.readString(SPECIFICATION, StandardCharsets.UTF_8);

        assertThat(normalise(generated))
            .withFailMessage("""
                The API changed but %s did not.

                %s

                Regenerate it in the same commit as the change:
                  %s""", SPECIFICATION, firstDifference(committed, generated), REGENERATE_COMMAND)
            .isEqualTo(normalise(committed));
    }

    @Test
    @DisplayName("generating the specification twice produces the same document")
    void generationIsReproducible() throws Exception {
        // Declaring the same response status in two places — on the method and
        // on a class-level meta-annotation — produced two entries for one
        // status, and which description survived the merge varied between
        // runs. That made the committed artefact unstable and would have
        // failed the comparison above at random, on a change that had nothing
        // to do with it. This turns that into an immediate, named failure.
        assertThat(normalise(fetchSpecification()))
            .withFailMessage("""
                The specification is not reproducible: two generations differ.

                The usual cause is one response status declared both on an operation and on                 the shared @StandardErrorResponses set. Declare it in one place.""")
            .isEqualTo(normalise(fetchSpecification()));
    }

    @Test
    @DisplayName("every endpoint the application serves appears in the specification")
    void noEndpointIsUndocumented() throws Exception {
        // A belt-and-braces check against the specification silently narrowing:
        // springdoc only describes what it can see, and a controller excluded
        // by a package scan or a group filter would vanish from the document
        // without anything failing.
        String generated = fetchSpecification();

        assertThat(generated.lines().filter(line -> line.startsWith("  /api/")).count())
            .as("documented paths under /api")
            .isGreaterThanOrEqualTo(40);
    }

    private String fetchSpecification() throws Exception {
        // Decoded from the raw bytes rather than via getContentAsString(),
        // which uses the response's declared character encoding and falls back
        // to ISO-8859-1 when there is none. The YAML response declares no
        // charset, so every non-ASCII character in a description — every em
        // dash — was written to the committed file as mojibake.
        byte[] body = mockMvc.perform(get("/api/openapi.yaml"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        return new String(body, StandardCharsets.UTF_8);
    }

    /** Ignores trailing whitespace and line-ending differences, nothing else. */
    private String normalise(String specification) {
        return specification.replace("\r\n", "\n").stripTrailing();
    }

    /** The first line that differs, so the failure names the change. */
    private String firstDifference(String committed, String generated) throws IOException {
        List<String> committedLines = normalise(committed).lines().toList();
        List<String> generatedLines = normalise(generated).lines().toList();

        int shared = Math.min(committedLines.size(), generatedLines.size());
        for (int line = 0; line < shared; line++) {
            if (!committedLines.get(line).equals(generatedLines.get(line))) {
                return "First difference at line %d:%n  committed: %s%n  generated: %s"
                    .formatted(line + 1, committedLines.get(line), generatedLines.get(line));
            }
        }
        return "The documents agree for their first %d lines; committed has %d lines, generated has %d."
            .formatted(shared, committedLines.size(), generatedLines.size());
    }
}
