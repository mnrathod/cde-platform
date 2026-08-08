package com.cde.platform.collaboration;

import com.cde.platform.collaboration.CollaborationEvent.Participant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Presence is keyed by socket session, not by person, and the cases that
 * matter are the ones where those differ: the same user in two tabs, and a
 * session that goes away without saying goodbye.
 */
class PresenceRegistryTest {

    private PresenceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PresenceRegistry();
    }

    private List<String> usernamesIn(Long documentId) {
        return registry.participants(documentId).stream().map(Participant::username).toList();
    }

    @Nested
    @DisplayName("joining")
    class Joining {

        @Test
        @DisplayName("lists the people viewing a document")
        void listsViewers() {
            registry.join(1L, "session-a", "ada");
            registry.join(1L, "session-b", "grace");

            assertThat(usernamesIn(1L)).containsExactly("ada", "grace");
        }

        @Test
        @DisplayName("keeps documents separate")
        void separatesDocuments() {
            registry.join(1L, "session-a", "ada");
            registry.join(2L, "session-b", "grace");

            assertThat(usernamesIn(1L)).containsExactly("ada");
            assertThat(usernamesIn(2L)).containsExactly("grace");
        }

        @Test
        @DisplayName("a document nobody is viewing has no participants")
        void emptyDocument() {
            assertThat(registry.participants(99L)).isEmpty();
        }

        @Test
        @DisplayName("lists someone once however many tabs they have open")
        void oneEntryPerPerson() {
            registry.join(1L, "tab-1", "ada");
            registry.join(1L, "tab-2", "ada");

            assertThat(usernamesIn(1L)).containsExactly("ada");
        }

        @Test
        @DisplayName("re-joining moves a session rather than leaving it in both")
        void rejoinMovesTheSession() {
            registry.join(1L, "session-a", "ada");
            registry.join(2L, "session-a", "ada");

            assertThat(registry.participants(1L)).isEmpty();
            assertThat(usernamesIn(2L)).containsExactly("ada");
        }
    }

    @Nested
    @DisplayName("leaving")
    class Leaving {

        @Test
        @DisplayName("removes the session and reports which document it left")
        void removesSession() {
            registry.join(1L, "session-a", "ada");
            registry.join(1L, "session-b", "grace");

            assertThat(registry.leave("session-a")).contains(1L);
            assertThat(usernamesIn(1L)).containsExactly("grace");
        }

        @Test
        @DisplayName("closing one tab does not announce that the person left")
        void closingOneTabKeepsThePerson() {
            registry.join(1L, "tab-1", "ada");
            registry.join(1L, "tab-2", "ada");

            registry.leave("tab-1");

            assertThat(usernamesIn(1L)).containsExactly("ada");
        }

        @Test
        @DisplayName("a session that was never registered is not an error")
        void unknownSession() {
            assertThat(registry.leave("never-seen")).isEmpty();
        }

        @Test
        @DisplayName("leaving twice is harmless")
        void leavingTwice() {
            registry.join(1L, "session-a", "ada");
            registry.leave("session-a");

            assertThat(registry.leave("session-a")).isEmpty();
        }

        @Test
        @DisplayName("the last person leaving empties the document")
        void lastPersonOut() {
            registry.join(1L, "session-a", "ada");
            registry.leave("session-a");

            assertThat(registry.participants(1L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("colours")
    class Colours {

        @Test
        @DisplayName("a user always gets the same colour, so clients agree without coordinating")
        void stablePerUser() {
            String first  = PresenceRegistry.colourFor("ada");
            String second = PresenceRegistry.colourFor("ada");

            assertThat(first).isEqualTo(second);
            assertThat(registry.join(1L, "s", "ada").get(0).colour()).isEqualTo(first);
        }

        @Test
        @DisplayName("is a colour, whatever the username hashes to")
        void alwaysAColour() {
            // Negative hash codes are the ones that would index out of bounds.
            for (String username : List.of("ada", "grace", "a", "", "zzzzzzzzzzzz", "Ω")) {
                assertThat(PresenceRegistry.colourFor(username)).matches("#[0-9a-f]{6}");
            }
        }
    }
}
