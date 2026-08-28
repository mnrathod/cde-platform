package com.cde.platform.openapi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Asserts that the error envelope the API actually returns is the one the
 * specification describes.
 *
 * <p>The two are built in different places — {@code ApiProblem} sets the
 * members, {@code OpenApiConfiguration} documents them — and nothing about
 * either would break if one gained a member the other did not. A client
 * generated from the specification would then find a field missing, or miss a
 * field that was there, in exactly the responses it most needs to parse.
 *
 * <p>So this drives real failures through real HTTP and compares what comes
 * back against the published schema, rather than checking either in isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProblemDetailContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("a validation failure returns every member the schema requires")
    void validationFailureMatchesTheDocumentedSchema() throws Exception {
        JsonNode problem = bodyOf(mockMvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}")));

        for (String required : documentedRequiredMembers()) {
            assertThat(problem.has(required))
                .as("the schema marks '%s' required, so every problem document must carry it; got %s",
                    required, problem)
                .isTrue();
        }

        assertThat(problem.get("status").asInt()).isEqualTo(422);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/validation-failed");
        assertThat(problem.get("traceId").asText()).matches("^[0-9a-f]{32}$");
        assertThat(problem.get("invalidFields")).isNotEmpty();
        assertThat(problem.get("invalidFields").get(0).get("field").asText()).isEqualTo("name");
    }

    @Test
    @WithMockUser
    @DisplayName("no member is returned that the schema does not describe")
    void nothingUndocumentedIsReturned() throws Exception {
        // The direction that a required-members check cannot catch: a member
        // added to ApiProblem and not to the schema is invisible to a
        // generated client, which is how a traceId nobody can read gets
        // shipped.
        JsonNode problem = bodyOf(mockMvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}")));

        List<String> documented = documentedMembers();
        List<String> undocumented = new ArrayList<>();
        problem.propertyNames().forEach(member -> {
            if (!documented.contains(member)) undocumented.add(member);
        });

        assertThat(undocumented)
            .as("these members are returned but not described in components/schemas/ProblemDetail")
            .isEmpty();
    }

    @Test
    @DisplayName("an unauthenticated request returns a problem document, not Boot's default error")
    void unauthenticatedRequestReturnsAProblemDocument() throws Exception {
        // Refused by the filter chain, so it never reaches the controller
        // advice. Before the entry point was replaced, this came back in a
        // shape the specification does not describe.
        MvcResult result = mockMvc.perform(get("/api/projects")).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(401, 403);
        assertThat(result.getResponse().getContentType())
            .as("problem documents carry the problem media type")
            .startsWith("application/problem+json");

        JsonNode problem = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(problem.has("type")).isTrue();
        assertThat(problem.has("traceId")).isTrue();
        assertThat(problem.has("path"))
            .as("'path' is Boot's default error format, not RFC 9457")
            .isFalse();
    }

    @Test
    @WithMockUser
    @DisplayName("the trace id in the body is the one in the response header")
    void theTraceIdIsTheSameOneTheHeaderCarries() throws Exception {
        // Two sources for one identifier is two chances to disagree, and a
        // support conversation quoting one while the logs hold the other is
        // worse than having neither.
        MvcResult result = mockMvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}")).andReturn();

        JsonNode problem = JSON.readTree(result.getResponse().getContentAsString());

        assertThat(problem.get("traceId").asText())
            .isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
    }

    @Test
    @WithMockUser
    @DisplayName("an inbound traceparent is adopted rather than replaced")
    void anInboundTraceparentIsAdopted() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        MvcResult result = mockMvc.perform(post("/api/projects")
            .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}")).andReturn();

        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("traceId").asText())
            .isEqualTo(traceId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-a-traceparent",
        "00-4bf92f3577b34da6-00f067aa0ba902b7-01",                      // trace id too short
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",      // all-zero is invalid
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",         // missing flags
        "INJECTED LOG LINE traceparent=whatever"
    })
    @WithMockUser
    @DisplayName("a traceparent that is not well formed is ignored rather than adopted")
    void aMalformedTraceparentIsIgnored(String traceparent) throws Exception {
        // The identifier labels every log line for the request, so a caller
        // able to put arbitrary text in it is a caller able to put arbitrary
        // text in the logs.
        MvcResult result = mockMvc.perform(post("/api/projects")
            .header("traceparent", traceparent)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}")).andReturn();

        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("traceId").asText())
            .as("a rejected traceparent must yield a freshly generated identifier")
            .matches("^[0-9a-f]{32}$");
    }

    @Test
    @WithMockUser
    @DisplayName("an error body never carries a stack trace or an internal type name")
    void errorsDoNotLeakInternals() throws Exception {
        String body = mockMvc.perform(post("/api/annotations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"documentId":1,"pageNumber":1,"type":"NOT_A_REAL_TYPE",
                     "shapeData":"{}","comment":"x"}"""))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .doesNotContain("com.cde.platform")
            .doesNotContain("Exception")
            .doesNotContain("at org.springframework");
    }

    private JsonNode bodyOf(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** Members the published schema marks required. */
    private List<String> documentedRequiredMembers() throws Exception {
        JsonNode schema = publishedProblemSchema();
        List<String> required = new ArrayList<>();
        schema.withArray("required").forEach(member -> required.add(member.asText()));
        return required;
    }

    /** Every member the published schema describes. */
    private List<String> documentedMembers() throws Exception {
        JsonNode properties = publishedProblemSchema().get("properties");
        List<String> members = new ArrayList<>();
        members.addAll(properties.propertyNames());
        return members;
    }

    private JsonNode publishedProblemSchema() throws Exception {
        String specification = mockMvc.perform(get("/api/openapi"))
            .andReturn().getResponse().getContentAsString();
        return JSON.readTree(specification)
            .get("components").get("schemas").get(ApiDocumentation.PROBLEM_SCHEMA);
    }
}
