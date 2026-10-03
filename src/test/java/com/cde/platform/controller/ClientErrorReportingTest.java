package com.cde.platform.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint a browser posts its own failures to.
 *
 * <p>Two things make it worth testing properly despite looking trivial. It is
 * the one route that needs no permission — an error worth reporting often
 * happens because the session has already failed, so demanding one would lose
 * exactly the reports that matter most — and that makes its body the only
 * entirely unauthenticated input the application writes to a log.
 *
 * <p>Which is the second thing: a caller that can put a newline into a logged
 * value can write log entries of its own choosing. Given a line-oriented log
 * shipped to a SIEM, a forged entry is a forged security record — a fabricated
 * authentication failure, a fabricated admin action — in the store §5.7 exists
 * to make trustworthy. So the stripping is asserted against every character a
 * log reader or a SIEM parser treats as a line break, not just {@code \\n},
 * and the assertions are on the log event rather than on the response, because
 * the response is a 202 whether or not the stripping happened.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("a client error report")
class ClientErrorReportingTest {

    @Autowired MockMvc mockMvc;

    private ListAppender<ILoggingEvent> captured;
    private Logger controllerLog;

    @BeforeEach
    void captureTheLog() {
        controllerLog = (Logger) LoggerFactory.getLogger(ErrorLogController.class);
        captured = new ListAppender<>();
        captured.start();
        controllerLog.addAppender(captured);
        controllerLog.setLevel(Level.TRACE);
    }

    @AfterEach
    void releaseTheLog() {
        controllerLog.detachAppender(captured);
        captured.stop();
    }

    private org.springframework.test.web.servlet.ResultActions report(String json)
            throws Exception {
        return mockMvc.perform(post("/api/logs/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
    }

    private ILoggingEvent onlyEvent() {
        assertThat(captured.list).hasSize(1);
        return captured.list.get(0);
    }

    /** The structured members, which is what a log aggregator indexes. */
    private String keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
            .filter(pair -> pair.key.equals(key))
            .map(pair -> String.valueOf(pair.value))
            .findFirst().orElse(null);
    }

    @Nested
    @DisplayName("is recorded at the level it names")
    class Levels {

        @ParameterizedTest(name = "a {0} report is logged at {1}")
        @CsvSource({"error, ERROR", "warning, WARN", "info, INFO"})
        @DisplayName("each level the client can name maps to its own log level")
        void levelsMap(String reported, String expected) throws Exception {
            // A client reporting an error that lands at INFO is a report nobody
            // will ever see, because the alerting is on level.
            report("""
                {"level":"%s","message":"the viewer failed to load"}""".formatted(reported))
                .andExpect(status().isAccepted());

            assertThat(onlyEvent().getLevel().toString()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a level nothing recognises is recorded rather than dropped")
        void anUnknownLevelIsStillRecorded() throws Exception {
            // Dropping it would lose the report over a spelling. INFO is the
            // honest floor: we do not know how serious the client thought it
            // was, so we do not claim it was serious.
            report("""
                {"level":"catastrophe","message":"the viewer failed to load"}""")
                .andExpect(status().isAccepted());

            assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
        }

        @Test
        @DisplayName("the level is matched case-sensitively, so a variant lands at the floor")
        void anUppercaseLevelLandsAtTheFloor() throws Exception {
            report("""
                {"level":"ERROR","message":"the viewer failed to load"}""")
                .andExpect(status().isAccepted());

            assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
        }
    }

    @Nested
    @DisplayName("cannot write log entries of its own")
    class LogInjection {

        /**
         * Every character a log reader or SIEM parser may treat as ending a
         * line: carriage return, line feed, NEL, and the Unicode line and
         * paragraph separators. A filter that handles only {@code \n} leaves
         * the rest as a way in.
         *
         * <p>Written as real characters rather than through a CSV source. A
         * {@code @CsvSource} does not interpret Java escapes, so {@code "\\n"}
         * there is a backslash followed by an n — which the stripping correctly
         * leaves alone, and the test would then be asserting nothing about line
         * breaks at all while appearing to cover five of them.
         */
        static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
                lineBreakingCharacters() {
            return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("\n",       "line feed"),
                org.junit.jupiter.params.provider.Arguments.of("\r",       "carriage return"),
                org.junit.jupiter.params.provider.Arguments.of("\r\n",     "a CRLF pair"),
                org.junit.jupiter.params.provider.Arguments.of("\u0085",   "next line"),
                org.junit.jupiter.params.provider.Arguments.of("\u2028",   "line separator"),
                org.junit.jupiter.params.provider.Arguments.of("\u2029",   "paragraph separator"));
        }

        @ParameterizedTest(name = "a {1} in the message does not start a new line")
        @org.junit.jupiter.params.provider.MethodSource("lineBreakingCharacters")
        @DisplayName("each line-breaking character is replaced")
        void lineBreakingCharactersAreStripped(String breaker, String name) throws Exception {
            String forged = "harmless" + breaker
                + "2026-10-03 00:00:00 ERROR Authentication failed for admin";

            var body = new tools.jackson.databind.ObjectMapper().createObjectNode();
            body.put("level", "error");
            body.put("message", forged);

            report(body.toString()).andExpect(status().isAccepted());

            String logged = onlyEvent().getFormattedMessage();
            assertThat(logged)
                .as("a %s would let the caller write a log line of its own", name)
                .doesNotContain(breaker);
            assertThat(logged).contains("harmless");
            // One line, so one entry: the forged text is still present as
            // content, which is correct — the point is that it cannot become a
            // separate record that reads as the server's own.
            assertThat(logged.lines().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a line break in the route is stripped too, not only in the message")
        void theRouteIsStrippedAsWell() throws Exception {
            // Every member reaches a log, so every member is a way in. Guarding
            // only the obvious one is the mistake this records.
            report("""
                {"level":"error","message":"failed","url":"/viewer\\n forged entry"}""")
                .andExpect(status().isAccepted());

            assertThat(keyValue(onlyEvent(), "clientRoute")).doesNotContain("\\n");
        }

        @Test
        @DisplayName("a line break in the reported type is stripped")
        void theTypeIsStripped() throws Exception {
            report("""
                {"level":"error","message":"failed","type":"TypeError\\n forged"}""")
                .andExpect(status().isAccepted());

            assertThat(keyValue(onlyEvent(), "clientType")).doesNotContain("\\n");
        }

        @Test
        @DisplayName("a line break in the client's timestamp is stripped")
        void theTimestampIsStripped() throws Exception {
            report("""
                {"level":"error","message":"failed","timestamp":"2026-10-03\\n forged"}""")
                .andExpect(status().isAccepted());

            assertThat(keyValue(onlyEvent(), "clientTimestamp")).doesNotContain("\\n");
        }

        @Test
        @DisplayName("members the client omitted are recorded as empty, not as the word null")
        void omittedMembersAreEmpty() throws Exception {
            // "null" in an indexed field reads as a value the client sent, and
            // a search for reports from a particular route would match it.
            report("""
                {"level":"error","message":"the viewer failed to load"}""")
                .andExpect(status().isAccepted());

            ILoggingEvent event = onlyEvent();
            assertThat(keyValue(event, "clientType")).isEmpty();
            assertThat(keyValue(event, "clientRoute")).isEmpty();
            assertThat(keyValue(event, "clientTimestamp")).isEmpty();
        }

        @Test
        @DisplayName("the report is marked as coming from a browser, so it cannot pose as server work")
        void theSourceIsRecorded() throws Exception {
            // An unauthenticated report sitting in the same log as the
            // application's own entries has to be distinguishable from them.
            report("""
                {"level":"error","message":"failed"}""")
                .andExpect(status().isAccepted());

            assertThat(keyValue(onlyEvent(), "source")).isEqualTo("browser");
        }
    }

    @Nested
    @DisplayName("is validated before it is logged")
    class Validation {

        @Test
        @DisplayName("a report with no message is refused")
        void messageIsRequired() throws Exception {
            report("""
                {"level":"error"}""").andExpect(status().isUnprocessableContent());

            assertThat(captured.list).isEmpty();
        }

        @Test
        @DisplayName("a report with no level is refused")
        void levelIsRequired() throws Exception {
            report("""
                {"message":"something failed"}""").andExpect(status().isUnprocessableContent());

            assertThat(captured.list).isEmpty();
        }

        @Test
        @DisplayName("an oversized message is refused rather than truncated into the log")
        void anOversizedMessageIsRefused() throws Exception {
            // The bound is the point: this route needs no permission, so
            // without one any client could fill the log store at will.
            report("""
                {"level":"error","message":"%s"}""".formatted("x".repeat(2001)))
                .andExpect(status().isUnprocessableContent());

            assertThat(captured.list).isEmpty();
        }

        @Test
        @DisplayName("a message at the limit is accepted")
        void aMessageAtTheLimitIsAccepted() throws Exception {
            report("""
                {"level":"error","message":"%s"}""".formatted("x".repeat(2000)))
                .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("an oversized route is refused")
        void anOversizedRouteIsRefused() throws Exception {
            report("""
                {"level":"error","message":"failed","url":"%s"}"""
                .formatted("/x".repeat(300)))
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("the refusal names which member was wrong")
        void theRefusalNamesTheMember() throws Exception {
            // §1.4: what happened, why, and what to do next. A client cannot
            // fix a report it is not told the shape of.
            String refusal = report("""
                {"level":"error"}""").andReturn().getResponse().getContentAsString();

            assertThat(refusal).contains("message");
        }
    }

    @Nested
    @DisplayName("needs no session")
    class Unauthenticated {

        @Test
        @DisplayName("is accepted with no credentials at all")
        void acceptedWithoutASession() throws Exception {
            // Deliberate, and the reason is in the description: the failures
            // most worth hearing about are the ones that broke the session. A
            // permission here would filter out exactly those.
            report("""
                {"level":"error","message":"the session expired mid-upload"}""")
                .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("is answered without waiting for the log write")
        void answeredWithoutWaiting() throws Exception {
            // 202 rather than 200: a client must never block its own error
            // handling on this succeeding, and telling it whether the write
            // landed would invite it to retry.
            report("""
                {"level":"error","message":"failed"}""")
                .andExpect(status().isAccepted());
        }
    }

    @Nested
    @DisplayName("records nothing it should not")
    class NoSensitiveData {

        @Test
        @DisplayName("the body is logged as named members, not as one opaque sentence")
        void membersAreIndexed() throws Exception {
            // §8.5 wants structured logs a SIEM can query. A single interpolated
            // string means every search is a substring match.
            report("""
                {"level":"error","message":"render failed","type":"TypeError",
                 "url":"/viewer/42","timestamp":"2026-10-03T00:00:00Z"}""")
                .andExpect(status().isAccepted());

            ILoggingEvent event = onlyEvent();
            assertThat(List.of("source", "clientType", "clientRoute", "clientTimestamp"))
                .allSatisfy(key -> assertThat(keyValue(event, key)).isNotNull());
        }

        @Test
        @DisplayName("an unknown member is accepted and discarded — it reaches no log")
        void unknownMembersReachNoLog() throws Exception {
            // §5.12 A03 asks for unknown JSON properties to be rejected, and
            // they are not: FAIL_ON_UNKNOWN_PROPERTIES is configured nowhere and
            // Spring Boot disables it, so this is the behaviour across the whole
            // API, not something peculiar to this route. Turning it on is a
            // breaking change for any client already sending an extra field
            // (§3.4), so it is recorded as a gap rather than changed here.
            //
            // What this does assert is the part that would be a security defect:
            // an unknown member is discarded, not swept into the log. Only the
            // named members are recorded, so a client cannot smuggle a token
            // into the log store under a field nobody is watching.
            report("""
                {"level":"error","message":"failed","sessionToken":"not-a-real-token"}""")
                .andExpect(status().isAccepted());

            ILoggingEvent event = onlyEvent();
            assertThat(event.getFormattedMessage()).doesNotContain("not-a-real-token");
            assertThat(event.getKeyValuePairs().stream().map(pair -> String.valueOf(pair.value)))
                .noneMatch(value -> value.contains("not-a-real-token"));
        }
    }
}
