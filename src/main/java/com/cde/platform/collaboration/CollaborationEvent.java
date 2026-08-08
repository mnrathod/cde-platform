package com.cde.platform.collaboration;

import com.cde.platform.dto.Dtos.AnnotationResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Everything broadcast to the people viewing a document.
 *
 * <p>A single envelope with a {@code type} discriminator rather than a
 * destination per event kind: a client wants one subscription per document
 * and to react to whatever arrives, not to track five subscriptions and
 * reconcile their ordering.
 *
 * <p>Null fields are omitted, so a cursor frame — the only one sent at any
 * volume — stays small.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollaborationEvent(
    Type                type,
    Long                documentId,
    String              actor,
    List<Participant>   participants,
    Cursor              cursor,
    AnnotationResponse  annotation,
    Long                annotationId,
    ReplyPayload        reply,
    Integer             version,
    String              summary,
    Instant             at
) {

    public enum Type {
        /** The full participant list, sent whenever it changes. */
        PRESENCE,
        /** One participant's pointer moved. */
        CURSOR,
        ANNOTATION_CREATED,
        ANNOTATION_UPDATED,
        ANNOTATION_DELETED,
        ANNOTATION_RESOLVED,
        REPLY_ADDED,
        /** A processing operation committed a new version of the document. */
        VERSION_COMMITTED
    }

    /**
     * Someone viewing the document.
     *
     * @param colour a stable per-user colour so their cursor and avatar match
     *               across clients and sessions
     */
    public record Participant(String username, String colour) {}

    /** A pointer position in PDF page coordinates, so zoom does not matter. */
    public record Cursor(int page, double x, double y) {}

    public record ReplyPayload(Long id, Long annotationId, String author, String content) {}

    // ── Factories ────────────────────────────────────────────────

    public static CollaborationEvent presence(Long documentId, List<Participant> participants) {
        return builder(Type.PRESENCE, documentId, null).participants(participants).build();
    }

    public static CollaborationEvent cursor(Long documentId, String actor, Cursor cursor) {
        return builder(Type.CURSOR, documentId, actor).cursor(cursor).build();
    }

    public static CollaborationEvent annotation(Type type, Long documentId, String actor,
                                                AnnotationResponse annotation) {
        return builder(type, documentId, actor).annotation(annotation).build();
    }

    public static CollaborationEvent annotationDeleted(Long documentId, String actor,
                                                       Long annotationId) {
        return builder(Type.ANNOTATION_DELETED, documentId, actor)
            .annotationId(annotationId).build();
    }

    public static CollaborationEvent replyAdded(Long documentId, String actor, ReplyPayload reply) {
        return builder(Type.REPLY_ADDED, documentId, actor).reply(reply).build();
    }

    public static CollaborationEvent versionCommitted(Long documentId, String actor,
                                                      Integer version, String summary) {
        return builder(Type.VERSION_COMMITTED, documentId, actor)
            .version(version).summary(summary).build();
    }

    private static Builder builder(Type type, Long documentId, String actor) {
        return new Builder(type, documentId, actor);
    }

    /** Keeps the factories readable without a constructor call full of nulls. */
    private static final class Builder {
        private final Type   type;
        private final Long   documentId;
        private final String actor;
        private List<Participant>  participants;
        private Cursor             cursor;
        private AnnotationResponse annotation;
        private Long               annotationId;
        private ReplyPayload       reply;
        private Integer            version;
        private String             summary;

        private Builder(Type type, Long documentId, String actor) {
            this.type = type; this.documentId = documentId; this.actor = actor;
        }

        Builder participants(List<Participant> value) { this.participants = value; return this; }
        Builder cursor(Cursor value)                  { this.cursor = value;       return this; }
        Builder annotation(AnnotationResponse value)  { this.annotation = value;   return this; }
        Builder annotationId(Long value)              { this.annotationId = value; return this; }
        Builder reply(ReplyPayload value)             { this.reply = value;        return this; }
        Builder version(Integer value)                { this.version = value;      return this; }
        Builder summary(String value)                 { this.summary = value;      return this; }

        CollaborationEvent build() {
            return new CollaborationEvent(type, documentId, actor, participants, cursor,
                annotation, annotationId, reply, version, summary, Instant.now());
        }
    }
}
