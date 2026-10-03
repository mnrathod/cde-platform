package com.cde.platform.collaboration;

import com.cde.platform.model.Document;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.security.StompAuthChannelInterceptor;
import com.cde.platform.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which documents a collaboration socket may touch.
 *
 * <p>The leak this guards was not subtle. The broker had no authorisation on
 * destinations at all, and {@code /topic/documents/{id}} names a document from
 * a single global sequence — so an authenticated caller in one tenant could
 * subscribe to another tenant's document and receive its presence list with
 * real usernames, its cursor traffic, and every annotation and reply broadcast
 * on it, content and author included. §5.6 calls a cross-tenant leak a
 * company-ending event; this was one, reachable by typing a different number.
 *
 * <p>Unit tests with a stubbed repository, because what needs pinning is the
 * decision rather than the plumbing: which destinations are recognised, what
 * happens when the tenant is unknown, and — the case an implementation is most
 * likely to miss — that sending is checked as well as subscribing.
 * {@link com.cde.platform.tenancy.TenantContext} is real, so the lookup is
 * genuinely performed as the connecting tenant.
 */
class CollaborationDestinationAuthorisationTest {

    private static final long OWN_TENANT = 7L;
    private static final long OWN_DOCUMENT = 41L;
    private static final long OTHER_TENANTS_DOCUMENT = 42L;

    private DocumentRepository documents;
    private CollaborationDestinationAuthorisation authorisation;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        authorisation = new CollaborationDestinationAuthorisation(documents);

        // Row-Level Security is what actually answers this in production: the
        // lookup runs with the connecting tenant bound, and another tenant's
        // row is not returned. The stub reproduces that rule rather than
        // answering the same way regardless of tenant, which would make every
        // assertion below pass for no reason.
        when(documents.findById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            boolean visible = TenantContext.currentTenantId()
                .map(tenant -> tenant == OWN_TENANT && id == OWN_DOCUMENT)
                .orElse(false);
            return visible ? Optional.of(new Document()) : Optional.empty();
        });
    }

    /** A frame from a session that connected as {@code tenantId}. */
    private Message<?> frame(StompCommand command, String destination, Long tenantId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        Map<String, Object> session = new HashMap<>();
        if (tenantId != null) {
            session.put(StompAuthChannelInterceptor.TENANT_ATTRIBUTE, tenantId);
        }
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> send(Message<?> message) {
        return authorisation.preSend(message, mock(MessageChannel.class));
    }

    private void assertRefused(StompCommand command, String destination, Long tenantId) {
        assertThatThrownBy(() -> send(frame(command, destination, tenantId)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertAllowed(StompCommand command, String destination, Long tenantId) {
        assertThat(send(frame(command, destination, tenantId))).isNotNull();
    }

    @Nested
    @DisplayName("subscribing to a document's traffic")
    class Subscribing {

        @Test
        @DisplayName("is allowed for a document the tenant can see")
        void allowsOwnDocument() {
            assertAllowed(StompCommand.SUBSCRIBE,
                "/topic/documents/" + OWN_DOCUMENT, OWN_TENANT);
        }

        @Test
        @DisplayName("is refused for another tenant's document")
        void refusesAnotherTenantsDocument() {
            // The leak itself. Everything the topic carries — usernames,
            // cursors, annotation and reply content — was readable this way.
            assertRefused(StompCommand.SUBSCRIBE,
                "/topic/documents/" + OTHER_TENANTS_DOCUMENT, OWN_TENANT);
        }

        @Test
        @DisplayName("is refused when the session's token named no tenant")
        void refusesWhenTenantUnknown() {
            // There is nothing to check against, and reading "unknown" as
            // "permitted" is exactly how the gap existed.
            assertRefused(StompCommand.SUBSCRIBE, "/topic/documents/" + OWN_DOCUMENT, null);
        }

        @Test
        @DisplayName("is refused for a document that does not exist")
        void refusesAbsentDocument() {
            assertRefused(StompCommand.SUBSCRIBE, "/topic/documents/999999", OWN_TENANT);
        }
    }

    @Nested
    @DisplayName("sending into a document's traffic")
    class Sending {

        @Test
        @DisplayName("is allowed for a document the tenant can see")
        void allowsOwnDocument() {
            assertAllowed(StompCommand.SEND,
                "/app/documents/" + OWN_DOCUMENT + "/join", OWN_TENANT);
        }

        @Test
        @DisplayName("is refused for another tenant's document")
        void refusesAnotherTenantsDocument() {
            // Guarding only SUBSCRIBE would have left this working: announcing
            // yourself into another tenant's participant list, where their
            // users would see the name.
            assertRefused(StompCommand.SEND,
                "/app/documents/" + OTHER_TENANTS_DOCUMENT + "/join", OWN_TENANT);
        }

        @Test
        @DisplayName("is refused for another tenant's document when reporting a cursor")
        void refusesCursorIntoAnotherTenantsDocument() {
            assertRefused(StompCommand.SEND,
                "/app/documents/" + OTHER_TENANTS_DOCUMENT + "/cursor", OWN_TENANT);
        }
    }

    @Nested
    @DisplayName("frames that are not about a document")
    class Unrelated {

        @Test
        @DisplayName("a CONNECT passes, because it is what records the tenant")
        void letsConnectThrough() {
            // Refusing this would close every socket before the tenant it is
            // checked against had been recorded.
            assertAllowed(StompCommand.CONNECT, null, null);
        }

        @Test
        @DisplayName("a destination naming no document passes")
        void letsOtherDestinationsThrough() {
            assertAllowed(StompCommand.SUBSCRIBE, "/topic/announcements", OWN_TENANT);
        }

        @Test
        @DisplayName("a destination with a non-numeric document passes unchecked")
        void letsUnparseableDestinationsThrough() {
            // It matches no document, so there is nothing to authorise; the
            // handler will not resolve it either.
            assertAllowed(StompCommand.SUBSCRIBE, "/topic/documents/abc", OWN_TENANT);
        }

        @Test
        @DisplayName("an unsubscribe passes")
        void letsUnsubscribeThrough() {
            assertAllowed(StompCommand.UNSUBSCRIBE,
                "/topic/documents/" + OTHER_TENANTS_DOCUMENT, OWN_TENANT);
        }
    }

    @Nested
    @DisplayName("how often the database is asked")
    class Caching {

        /** One session, reused across frames, as a real socket would be. */
        private Message<?> onSession(Map<String, Object> session,
                                     StompCommand command, String destination) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
            accessor.setDestination(destination);
            accessor.setSessionAttributes(session);
            accessor.setLeaveMutable(true);
            return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        }

        private Map<String, Object> connectedSession(long tenantId) {
            Map<String, Object> session = new HashMap<>();
            session.put(StompAuthChannelInterceptor.TENANT_ATTRIBUTE, tenantId);
            return session;
        }

        @Test
        @DisplayName("a cleared document is not looked up again on the next frame")
        void remembersWhatItAlreadyCleared() {
            // The browser throttles cursor frames to one every 60 ms. Asking
            // the database per frame would put roughly a hundred and sixty
            // queries a second through the pool for ten people moving their
            // pointers — a worse defect than the leak being fixed.
            Map<String, Object> session = connectedSession(OWN_TENANT);

            send(onSession(session, StompCommand.SUBSCRIBE, "/topic/documents/" + OWN_DOCUMENT));
            for (int frame = 0; frame < 20; frame++) {
                send(onSession(session, StompCommand.SEND,
                               "/app/documents/" + OWN_DOCUMENT + "/cursor"));
            }

            org.mockito.Mockito.verify(documents, org.mockito.Mockito.times(1))
                .findById(OWN_DOCUMENT);
        }

        @Test
        @DisplayName("a refusal is never remembered, so it is re-checked every time")
        void neverRemembersARefusal() {
            // Caching a refusal would be harmless; caching only successes is
            // what keeps the set from being grown by a caller enumerating
            // identifiers, and it means nothing cached can keep somebody in.
            Map<String, Object> session = connectedSession(OWN_TENANT);

            for (int attempt = 0; attempt < 3; attempt++) {
                assertThatThrownBy(() -> send(onSession(session, StompCommand.SUBSCRIBE,
                        "/topic/documents/" + OTHER_TENANTS_DOCUMENT)))
                    .isInstanceOf(IllegalArgumentException.class);
            }

            org.mockito.Mockito.verify(documents, org.mockito.Mockito.times(3))
                .findById(OTHER_TENANTS_DOCUMENT);
        }

        @Test
        @DisplayName("what one socket cleared does not clear it for another")
        void doesNotShareBetweenSessions() {
            // The set lives in the session attributes, so it is bounded by the
            // connection. A static cache here would be a cross-tenant leak of
            // its own.
            send(onSession(connectedSession(OWN_TENANT), StompCommand.SUBSCRIBE,
                           "/topic/documents/" + OWN_DOCUMENT));

            assertThatThrownBy(() -> send(onSession(connectedSession(99L),
                    StompCommand.SUBSCRIBE, "/topic/documents/" + OWN_DOCUMENT)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the tenant bound during the check does not outlive it")
    void doesNotLeaveTheTenantBound() {
        // callAsTenant restores in a finally, and this asserts the consequence
        // rather than trusting it: these run on pooled threads, and a tenant
        // left bound is inherited by whatever unrelated work picks the thread
        // up next — a cross-tenant leak with no bug anywhere near it.
        send(frame(StompCommand.SUBSCRIBE, "/topic/documents/" + OWN_DOCUMENT, OWN_TENANT));

        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    // ── The edges of the destination match and the session's state ────────

    @Nested
    @DisplayName("frames the guard has nothing to work with")
    class DegenerateFrames {

        @Test
        @DisplayName("a frame with no STOMP headers at all passes through")
        void aFrameWithNoAccessorPasses() {
            // Not every message on the channel is a STOMP frame. Refusing what
            // cannot be read would break the broker's own internal traffic, and
            // nothing in such a message names a document to leak.
            Message<?> plain = MessageBuilder.withPayload(new byte[0]).build();

            assertThat(authorisation.preSend(plain, mock(MessageChannel.class))).isNotNull();
        }

        @Test
        @DisplayName("a frame with no destination passes")
        void aFrameWithNoDestinationPasses() {
            assertAllowed(StompCommand.SUBSCRIBE, null, OWN_TENANT);
        }

        @Test
        @DisplayName("a session with no attributes at all is refused, not waved through")
        void aSessionWithoutAttributesIsRefused() {
            // There is nothing to check the document against. "Unknown" read as
            // "permitted" is precisely the shape of the defect this guard
            // exists to close, so the absence of a session has to refuse.
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/documents/" + OWN_DOCUMENT);
            accessor.setLeaveMutable(true);
            Message<?> noSession =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> send(noSession))
                .isInstanceOf(IllegalArgumentException.class);

            // And refused without a lookup. Asserting only the refusal is too
            // weak: a guard that substituted some placeholder tenant would also
            // refuse, because no document is visible under a tenant that does
            // not exist — the test would pass while the guard had stopped
            // checking what it is for. No query at all is the behaviour that
            // distinguishes the two.
            org.mockito.Mockito.verify(documents, org.mockito.Mockito.never())
                .findById(org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("a tenant recorded as something other than a number is refused")
        void aNonNumericTenantIsRefused() {
            // The attribute is written by the CONNECT interceptor, so a value of
            // the wrong type means something upstream changed. Refusing is the
            // only safe reading of a tenant identifier that cannot be read.
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/documents/" + OWN_DOCUMENT);
            Map<String, Object> session = new HashMap<>();
            session.put(StompAuthChannelInterceptor.TENANT_ATTRIBUTE, "7");
            accessor.setSessionAttributes(session);
            accessor.setLeaveMutable(true);

            assertThatThrownBy(() -> send(
                    MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders())))
                .isInstanceOf(IllegalArgumentException.class);

            org.mockito.Mockito.verify(documents, org.mockito.Mockito.never())
                .findById(org.mockito.ArgumentMatchers.anyLong());
        }
    }

    @Nested
    @DisplayName("which destinations name a document")
    class DestinationMatching {

        @Test
        @DisplayName("a document id too large for a long is not matched as a document")
        void anOversizedIdIsNotADocument() {
            // It matches the digits in the pattern but no document can have it,
            // so there is nothing to authorise. Letting the parse failure
            // escape would turn a nonsense subscription into a broker error
            // rather than a quiet pass.
            assertAllowed(StompCommand.SUBSCRIBE,
                "/topic/documents/99999999999999999999999", OWN_TENANT);
        }

        @Test
        @DisplayName("a sub-path under a document is still checked against that document")
        void aSubPathIsStillChecked() {
            // /topic/documents/42/cursors carries the same information as
            // /topic/documents/42, so a pattern anchored to the exact path
            // would leave every sub-topic unguarded.
            assertRefused(StompCommand.SUBSCRIBE,
                "/topic/documents/" + OTHER_TENANTS_DOCUMENT + "/cursors", OWN_TENANT);
        }

        @Test
        @DisplayName("the app prefix is checked as well as the topic prefix")
        void theAppPrefixIsChecked() {
            // Clients send to /app and subscribe to /topic. Guarding only one
            // leaves the other open, and /app is the writing side.
            assertRefused(StompCommand.SEND,
                "/app/documents/" + OTHER_TENANTS_DOCUMENT, OWN_TENANT);
        }

        @Test
        @DisplayName("a destination that merely contains a document path is not matched")
        void aDestinationThatOnlyContainsThePathIsNotMatched() {
            // The pattern is anchored at both ends. A prefix match would let
            // /topic/documents/41/../42 style destinations, or a queue named
            // after one, be judged against the wrong document.
            assertAllowed(StompCommand.SUBSCRIBE,
                "/queue/private/topic/documents/" + OTHER_TENANTS_DOCUMENT, OWN_TENANT);
        }

        @Test
        @DisplayName("a document path with no id is not matched")
        void aPathWithNoIdIsNotMatched() {
            assertAllowed(StompCommand.SUBSCRIBE, "/topic/documents/", OWN_TENANT);
        }
    }
}
