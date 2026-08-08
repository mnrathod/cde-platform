package com.cde.platform.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;

/**
 * Live collaboration on a document.
 *
 * <pre>
 *   SEND      /app/documents/{id}/join     — announce arrival
 *   SEND      /app/documents/{id}/cursor   — report pointer position
 *   SUBSCRIBE /topic/documents/{id}        — receive everything
 * </pre>
 *
 * <p>There is no explicit leave: a browser that crashes or a laptop that
 * sleeps never sends one, so departure is driven by the session disconnect
 * instead. Handling only the polite case would leave ghost participants
 * listed forever.
 */
@Controller
public class CollaborationController {

    private static final Logger log = LoggerFactory.getLogger(CollaborationController.class);

    private final PresenceRegistry        presence;
    private final CollaborationBroadcaster broadcaster;

    public CollaborationController(PresenceRegistry presence,
                                   CollaborationBroadcaster broadcaster) {
        this.presence    = presence;
        this.broadcaster = broadcaster;
    }

    @MessageMapping("/documents/{documentId}/join")
    public void join(@DestinationVariable Long documentId,
                     Principal principal,
                     SimpMessageHeaderAccessor headers) {
        String username  = usernameOf(principal);
        String sessionId = headers.getSessionId();
        if (username == null || sessionId == null) return;

        List<CollaborationEvent.Participant> participants =
            presence.join(documentId, sessionId, username);
        broadcaster.presenceChanged(documentId, participants);

        log.debug("{} joined document {} ({} viewing)", username, documentId, participants.size());
    }

    /**
     * Relays a pointer position. Coordinates arrive in PDF page space, so
     * they land in the same place for a viewer at a different zoom.
     *
     * <p>Deliberately not persisted and not replayed to late joiners: a
     * cursor is only meaningful while it is moving.
     */
    @MessageMapping("/documents/{documentId}/cursor")
    public void cursor(@DestinationVariable Long documentId,
                       CollaborationEvent.Cursor cursor,
                       Principal principal) {
        String username = usernameOf(principal);
        if (username == null || cursor == null) return;
        broadcaster.cursorMoved(documentId, username, cursor);
    }

    /**
     * Removes a participant when their socket closes, for any reason —
     * navigating away, a closed tab, a dropped network.
     */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presence.leave(event.getSessionId()).ifPresent(documentId ->
            broadcaster.presenceChanged(documentId, presence.participants(documentId)));
    }

    private String usernameOf(Principal principal) {
        return principal != null ? principal.getName() : null;
    }
}
