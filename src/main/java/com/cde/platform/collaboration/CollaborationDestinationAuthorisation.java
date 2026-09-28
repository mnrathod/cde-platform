package com.cde.platform.collaboration;

import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.security.StompAuthChannelInterceptor;
import com.cde.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses a collaboration frame for a document the connecting tenant cannot
 * see.
 *
 * <p>Authenticating the socket was not enough, and the gap this closes was a
 * cross-tenant leak of customer content. The broker was a plain in-memory one
 * with no authorisation on destinations, and the topic name is
 * {@code /topic/documents/{id}} where the id comes from a single global
 * sequence. So any authenticated caller — any tenant's — could subscribe to
 * {@code /topic/documents/41} and receive that document's presence list
 * (usernames), cursor positions, and every annotation and reply broadcast on
 * it, content included. Sending to {@code /app/documents/41/join} put them in
 * somebody else's participant list.
 *
 * <p>The check asks the database under the caller's own tenant, so Row-Level
 * Security answers it: a document belonging to another tenant simply is not
 * found. No {@code WHERE tenant_id} is written here, which §5.6 forbids
 * precisely so that a forgotten one cannot be the thing standing between two
 * customers.
 *
 * <p>Both directions are checked. Guarding SUBSCRIBE alone would stop the
 * reading half and leave the writing half — announcing yourself into another
 * tenant's document — working.
 */
@Component
public class CollaborationDestinationAuthorisation implements ChannelInterceptor {

    private static final Logger log =
        LoggerFactory.getLogger(CollaborationDestinationAuthorisation.class);

    /** {@code /topic/documents/41} and {@code /app/documents/41/cursor} alike. */
    private static final Pattern DOCUMENT_DESTINATION =
        Pattern.compile("^/(?:topic|app)/documents/(\\d+)(?:/.*)?$");

    /** Where {@link #settledFor} keeps this session's cleared documents. */
    private static final String SETTLED_ATTRIBUTE = "cde.collaboration.settled";

    /**
     * How many documents one socket may be cleared for before the check stops
     * being remembered. A person views a handful; anything approaching this is
     * enumeration, and re-checking costs it a query per frame.
     */
    private static final int MAX_REMEMBERED_DOCUMENTS = 64;

    private final DocumentRepository documents;

    public CollaborationDestinationAuthorisation(@Lazy DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();
        if (!StompCommand.SUBSCRIBE.equals(command) && !StompCommand.SEND.equals(command)) {
            return message;
        }

        Optional<Long> documentId = documentIn(accessor.getDestination());
        if (documentId.isEmpty()) {
            // A destination that names no document — nothing here is about it.
            return message;
        }

        if (!isVisibleToConnectedTenant(accessor, documentId.get())) {
            // Refused rather than silently dropped: a client that believes it
            // is subscribed and receives nothing is indistinguishable from an
            // idle document, and would retry forever.
            log.warn("Refused a {} to document {} from a session that cannot see it",
                     command, documentId.get());
            throw new IllegalArgumentException(
                "That document is not available on this connection.");
        }
        return message;
    }

    /** The document a collaboration destination refers to, if it names one. */
    private static Optional<Long> documentIn(String destination) {
        if (destination == null) return Optional.empty();
        var matcher = DOCUMENT_DESTINATION.matcher(destination);
        if (!matcher.matches()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            // A number too long for a long. It matches no document either way.
            return Optional.empty();
        }
    }

    private boolean isVisibleToConnectedTenant(StompHeaderAccessor accessor, long documentId) {
        Optional<Long> tenantId = connectedTenant(accessor);
        if (tenantId.isEmpty()) {
            // The socket authenticated but its token named no tenant. Refusing
            // is the only safe reading: there is nothing to check against, and
            // treating "unknown" as "permitted" is how this defect existed.
            return false;
        }

        Set<Long> settled = settledFor(accessor);
        if (settled != null && settled.contains(documentId)) {
            return true;
        }

        boolean visible = TenantContext.callAsTenant(tenantId.get(),
            () -> documents.findById(documentId).isPresent());

        if (visible && settled != null && settled.size() < MAX_REMEMBERED_DOCUMENTS) {
            settled.add(documentId);
        }
        return visible;
    }

    /**
     * Documents this session has already been cleared for.
     *
     * <p>Without this the check is a database query per frame, and the frames
     * that matter are cursor positions: the browser throttles them to one
     * every 60 ms, so ten people on a drawing would have put something like a
     * hundred and sixty queries a second through the connection pool for
     * pointer movement alone. That would have been a worse defect than the one
     * being fixed.
     *
     * <p>Only successes are remembered, and only within one socket. A refusal
     * is re-checked every time, so nothing is cached that would keep somebody
     * in after their access ended; and the session is bounded by the
     * connection, so the set cannot outlive it. The cap is there because the
     * key is client-supplied: a caller enumerating ids would otherwise grow
     * this without limit, and a refused id is never added anyway.
     *
     * <p>Access to a document changing mid-socket is the acknowledged cost. It
     * is bounded by the life of the connection rather than by a TTL, which is
     * the same bound the presence list already has.
     */
    private static Set<Long> settledFor(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) return null;
        Object existing = attributes.get(SETTLED_ATTRIBUTE);
        if (existing instanceof Set<?> set) {
            @SuppressWarnings("unchecked")
            Set<Long> settled = (Set<Long>) set;
            return settled;
        }
        // The map is the session's, touched by whichever thread carries a
        // frame, so the set inside it has to tolerate that too.
        Set<Long> settled = java.util.concurrent.ConcurrentHashMap.newKeySet();
        attributes.put(SETTLED_ATTRIBUTE, settled);
        return settled;
    }

    private static Optional<Long> connectedTenant(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) return Optional.empty();
        Object tenantId = attributes.get(StompAuthChannelInterceptor.TENANT_ATTRIBUTE);
        return tenantId instanceof Long value ? Optional.of(value) : Optional.empty();
    }
}
