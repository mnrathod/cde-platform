package com.cde.platform.collaboration;

import com.cde.platform.collaboration.CollaborationEvent.Participant;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently viewing each document.
 *
 * <p>Keyed by WebSocket session rather than by username, because the same
 * person legitimately has the document open in two tabs and closing one must
 * not announce that they left.
 *
 * <p>State lives in memory alongside the simple STOMP broker. That is
 * consistent — both are per-instance — but it does mean presence is only
 * correct while the application runs as a single replica; see
 * {@link com.cde.platform.config.WebSocketConfig} for what multi-replica
 * needs.
 */
@Component
public class PresenceRegistry {

    /**
     * Colours for participant avatars and cursors. Chosen to stay
     * distinguishable against a white page and from each other, including for
     * the commonest forms of colour blindness — cursors are identified by
     * colour, so two participants must never be mistaken for one.
     */
    private static final String[] PALETTE = {
        "#1f77b4", "#d62728", "#2ca02c", "#ff7f0e",
        "#9467bd", "#8c564b", "#17becf", "#e377c2"
    };

    /** documentId -> sessionId -> username. */
    private final Map<Long, Map<String, String>> byDocument = new ConcurrentHashMap<>();

    /** sessionId -> documentId, so a disconnect can be resolved without a scan. */
    private final Map<String, Long> sessionDocuments = new ConcurrentHashMap<>();

    /**
     * Records that a session is viewing a document.
     *
     * @return the document's participants after the join
     */
    public List<Participant> join(Long documentId, String sessionId, String username) {
        // A session can only be in one document; re-joining moves it.
        leave(sessionId);
        byDocument.computeIfAbsent(documentId, key -> new ConcurrentHashMap<>())
                  .put(sessionId, username);
        sessionDocuments.put(sessionId, documentId);
        return participants(documentId);
    }

    /**
     * Removes a session, wherever it was.
     *
     * @return the document it was viewing, empty if it was viewing none
     */
    public Optional<Long> leave(String sessionId) {
        Long documentId = sessionDocuments.remove(sessionId);
        if (documentId == null) return Optional.empty();

        Map<String, String> sessions = byDocument.get(documentId);
        if (sessions != null) {
            sessions.remove(sessionId);
            // Drop the document's entry once empty, so a long-running server
            // does not accumulate a map per document ever opened.
            if (sessions.isEmpty()) byDocument.remove(documentId, sessions);
        }
        return Optional.of(documentId);
    }

    /** Distinct people viewing a document, in a stable order. */
    public List<Participant> participants(Long documentId) {
        Map<String, String> sessions = byDocument.get(documentId);
        if (sessions == null) return List.of();

        return sessions.values().stream()
            .distinct()
            .sorted(Comparator.naturalOrder())
            .map(username -> new Participant(username, colourFor(username)))
            .toList();
    }

    /**
     * The colour identifying a user, derived from their name so every client
     * and every session agrees on it without having to distribute a mapping.
     */
    public static String colourFor(String username) {
        int index = Math.floorMod(username.hashCode(), PALETTE.length);
        return PALETTE[index];
    }
}
