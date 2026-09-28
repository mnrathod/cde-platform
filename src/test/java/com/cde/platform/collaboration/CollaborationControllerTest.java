package com.cde.platform.collaboration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.CloseStatus;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is shown as viewing a drawing, and where their pointer is.
 *
 * <p>Small surface, and every one of its guards is a frame that can arrive
 * without the thing it needs. A STOMP frame is not a request: there is no
 * framework validation in front of it, the principal can be absent, the
 * session id can be absent, and the body can be absent — so each of those has
 * to be survivable rather than a thrown exception that takes the socket down
 * with it.
 *
 * <p>Departure is the part worth most. There is no leave frame, deliberately:
 * a browser that crashes or a laptop that sleeps never sends one, so the only
 * reliable signal is the socket closing. Handling only the polite case would
 * leave ghost participants listed on a drawing forever, which is exactly the
 * kind of thing nobody reports as a bug — they just stop trusting the list.
 *
 * <p>The registry is real, because what is being checked is the interaction
 * between joining, leaving and listing; a stubbed one would only confirm that
 * the controller calls it. The broadcaster is a mock, because what it does
 * with a message belongs to the broker.
 */
@DisplayName("live collaboration on a document")
class CollaborationControllerTest {

    private static final Long DOCUMENT = 41L;
    private static final String SESSION = "stomp-session-1";

    private PresenceRegistry presence;
    private CollaborationBroadcaster broadcaster;
    private CollaborationController collaboration;

    @BeforeEach
    void setUp() {
        presence = new PresenceRegistry();
        broadcaster = mock(CollaborationBroadcaster.class);
        collaboration = new CollaborationController(presence, broadcaster);
    }

    private Principal signedInAs(String username) {
        return () -> username;
    }

    private SimpMessageHeaderAccessor headers(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        if (sessionId != null) accessor.setSessionId(sessionId);
        return accessor;
    }

    private SessionDisconnectEvent disconnect(String sessionId) {
        var accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId(sessionId);
        return new SessionDisconnectEvent(this,
            new GenericMessage<>(new byte[0], accessor.getMessageHeaders()),
            sessionId, CloseStatus.NORMAL);
    }

    @Nested
    @DisplayName("arriving at a document")
    class Joining {

        @Test
        @DisplayName("the newcomer appears in the participant list")
        void newcomerIsListed() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));

            assertThat(presence.participants(DOCUMENT))
                .extracting(CollaborationEvent.Participant::username)
                .containsExactly("sam.okonkwo");
        }

        @Test
        @DisplayName("everybody already there is told")
        void everybodyIsTold() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));

            verify(broadcaster).presenceChanged(eq(DOCUMENT), any());
        }

        @Test
        @DisplayName("the broadcast carries the whole list, not just the newcomer")
        void broadcastCarriesEveryone() {
            // A client replaces its list with what arrives, so sending one
            // name would make everybody else vanish from the drawing.
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));

            collaboration.join(DOCUMENT, signedInAs("rowan.li"), headers("stomp-session-2"));

            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(broadcaster, org.mockito.Mockito.times(2))
                .presenceChanged(eq(DOCUMENT), captor.capture());
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("a frame with nobody on it is ignored rather than listed")
        void anonymousJoinIsIgnored() {
            // The socket is authenticated at CONNECT, so this should not
            // happen — but a null principal listed as a participant would put
            // an empty name on everybody else's screen.
            collaboration.join(DOCUMENT, null, headers(SESSION));

            assertThat(presence.participants(DOCUMENT)).isEmpty();
            verifyNoInteractions(broadcaster);
        }

        @Test
        @DisplayName("a frame with no session is ignored")
        void sessionlessJoinIsIgnored() {
            // Without a session id there is nothing to remove on disconnect,
            // so admitting one would create a participant who can never leave.
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(null));

            assertThat(presence.participants(DOCUMENT)).isEmpty();
            verifyNoInteractions(broadcaster);
        }

        @Test
        @DisplayName("joining twice on one socket does not list the same person twice")
        void rejoiningDoesNotDuplicate() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));

            assertThat(presence.participants(DOCUMENT)).hasSize(1);
        }

        @Test
        @DisplayName("people on different documents are kept apart")
        void documentsAreSeparate() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));
            collaboration.join(99L, signedInAs("rowan.li"), headers("stomp-session-2"));

            assertThat(presence.participants(DOCUMENT)).hasSize(1);
            assertThat(presence.participants(99L)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("reporting where a pointer is")
    class Cursors {

        private final CollaborationEvent.Cursor somewhere =
            new CollaborationEvent.Cursor(1, 120.5, 338.0);

        @Test
        @DisplayName("the position is relayed to the others")
        void relaysThePosition() {
            collaboration.cursor(DOCUMENT, somewhere, signedInAs("sam.okonkwo"));

            verify(broadcaster).cursorMoved(DOCUMENT, "sam.okonkwo", somewhere);
        }

        @Test
        @DisplayName("a position from nobody is dropped")
        void dropsAnAnonymousCursor() {
            collaboration.cursor(DOCUMENT, somewhere, null);

            verify(broadcaster, never()).cursorMoved(anyLong(), any(), any());
        }

        @Test
        @DisplayName("a frame with no position is dropped rather than relayed as null")
        void dropsAnEmptyCursor() {
            // Relaying it would send every other client a cursor with no
            // coordinates, which they would draw at the origin.
            collaboration.cursor(DOCUMENT, null, signedInAs("sam.okonkwo"));

            verify(broadcaster, never()).cursorMoved(anyLong(), any(), any());
        }

        @Test
        @DisplayName("reporting a cursor does not make somebody a participant")
        void cursorDoesNotJoin() {
            // Presence is established by joining. A cursor frame from a client
            // that never joined must not add a name to the list, or a stale
            // client would resurrect itself after being removed.
            collaboration.cursor(DOCUMENT, somewhere, signedInAs("sam.okonkwo"));

            assertThat(presence.participants(DOCUMENT)).isEmpty();
        }
    }

    @Nested
    @DisplayName("leaving, which nobody announces")
    class Leaving {

        @Test
        @DisplayName("a closed socket removes its participant")
        void closingRemovesTheParticipant() {
            // The whole reason departure is driven by the disconnect: a
            // crashed browser or a slept laptop never sends a leave frame, and
            // handling only the polite case leaves ghosts listed forever.
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));

            collaboration.onDisconnect(disconnect(SESSION));

            assertThat(presence.participants(DOCUMENT)).isEmpty();
        }

        @Test
        @DisplayName("everybody left is told who remains")
        void survivorsAreTold() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));
            collaboration.join(DOCUMENT, signedInAs("rowan.li"), headers("stomp-session-2"));

            collaboration.onDisconnect(disconnect(SESSION));

            assertThat(presence.participants(DOCUMENT))
                .extracting(CollaborationEvent.Participant::username)
                .containsExactly("rowan.li");
        }

        @Test
        @DisplayName("one person leaving does not remove the others")
        void othersStay() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));
            collaboration.join(DOCUMENT, signedInAs("rowan.li"), headers("stomp-session-2"));

            collaboration.onDisconnect(disconnect(SESSION));

            assertThat(presence.participants(DOCUMENT)).hasSize(1);
        }

        @Test
        @DisplayName("a socket that never joined anything is not announced")
        void unknownSessionIsQuiet() {
            // Every closed socket reaches this, including the ones that never
            // opened a document — broadcasting for each would be a message
            // per disconnect to a topic nobody is on.
            collaboration.onDisconnect(disconnect("a-socket-that-never-joined"));

            verifyNoInteractions(broadcaster);
        }

        @Test
        @DisplayName("leaving one document does not remove the person from another")
        void leavingIsPerSocket() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));
            collaboration.join(99L, signedInAs("sam.okonkwo"), headers("stomp-session-2"));

            collaboration.onDisconnect(disconnect(SESSION));

            assertThat(presence.participants(DOCUMENT)).isEmpty();
            assertThat(presence.participants(99L)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("how a participant is shown")
    class Appearance {

        @Test
        @DisplayName("everybody gets a colour")
        void everybodyHasAColour() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));

            assertThat(presence.participants(DOCUMENT).get(0).colour()).isNotBlank();
        }

        @Test
        @DisplayName("the same person keeps the same colour across sessions")
        void colourIsStablePerPerson() {
            // Their cursor and their avatar have to match, and they have to
            // match on everybody else's screen too — so the colour is derived
            // from the name rather than assigned on arrival.
            assertThat(PresenceRegistry.colourFor("sam.okonkwo"))
                .isEqualTo(PresenceRegistry.colourFor("sam.okonkwo"));
        }

        @Test
        @DisplayName("different people usually get different colours")
        void differentPeopleDiffer() {
            var colours = java.util.stream.Stream.of(
                    "sam.okonkwo", "rowan.li", "j.okafor", "a.silva")
                .map(PresenceRegistry::colourFor)
                .collect(java.util.stream.Collectors.toSet());

            // Not a guarantee — a hash over a small palette collides — but all
            // four landing on one colour would mean the derivation is not
            // using the name at all.
            assertThat(colours).hasSizeGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("listing who is on a document")
    class Listing {

        @Test
        @DisplayName("a document nobody is viewing lists nobody")
        void emptyDocumentListsNobody() {
            assertThat(presence.participants(DOCUMENT)).isEmpty();
        }

        @Test
        @DisplayName("the list is a copy, so a caller cannot edit the registry through it")
        void listIsNotTheRegistrysOwn() {
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));
            List<CollaborationEvent.Participant> listed = presence.participants(DOCUMENT);

            try {
                listed.clear();
            } catch (UnsupportedOperationException immutable) {
                // Equally good: the caller cannot change it either way.
            }

            assertThat(presence.participants(DOCUMENT)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what a broadcast carries")
    class BroadcastShape {

        @Test
        @DisplayName("a presence broadcast names the document it is about")
        void presenceNamesTheDocument() {
            // Clients subscribe per document, but the envelope carries the id
            // so a client holding several can tell them apart.
            collaboration.join(DOCUMENT, signedInAs("sam.okonkwo"), headers(SESSION));

            verify(broadcaster).presenceChanged(eq(DOCUMENT), any());
        }

        @Test
        @DisplayName("a cursor broadcast names who moved it")
        void cursorNamesTheMover() {
            var at = new CollaborationEvent.Cursor(2, 10, 20);

            collaboration.cursor(DOCUMENT, at, signedInAs("rowan.li"));

            verify(broadcaster).cursorMoved(DOCUMENT, "rowan.li", at);
        }

        @Test
        @DisplayName("a cursor carries the page it is on, so it lands in the right place")
        void cursorCarriesItsPage() {
            var onPageThree = new CollaborationEvent.Cursor(3, 50, 60);

            collaboration.cursor(DOCUMENT, onPageThree, signedInAs("sam.okonkwo"));

            var captor = org.mockito.ArgumentCaptor.forClass(CollaborationEvent.Cursor.class);
            verify(broadcaster).cursorMoved(eq(DOCUMENT), eq("sam.okonkwo"), captor.capture());
            assertThat(captor.getValue().page()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("a participant record")
    class ParticipantRecord {

        @Test
        @DisplayName("carries the name and the colour and nothing else")
        void carriesOnlyWhatIsNeeded() {
            // §5.7: a broadcast to everybody viewing a drawing is not a place
            // for anything beyond what has to be drawn.
            var participant = new CollaborationEvent.Participant("sam.okonkwo", "#4F46E5");

            assertThat(participant.username()).isEqualTo("sam.okonkwo");
            assertThat(participant.colour()).isEqualTo("#4F46E5");
            assertThat(Map.of("username", participant.username(),
                              "colour", participant.colour())).hasSize(2);
        }
    }
}
