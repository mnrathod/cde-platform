package com.cde.platform.collaboration;

import com.cde.platform.dto.AnnotationDtos.AnnotationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Publishes collaboration events to everyone viewing a document.
 *
 * <p>Exists so the REST controllers do not each build destination strings and
 * hold a {@link SimpMessagingTemplate}: they say what happened, this decides
 * where it goes. It also means a broadcast failure can never fail the request
 * that triggered it — an annotation that saved but was not announced is a
 * missed refresh, whereas a 500 would lose the user's work.
 */
@Service
public class CollaborationBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(CollaborationBroadcaster.class);

    private static final String DOCUMENT_TOPIC = "/topic/documents/";

    private final SimpMessagingTemplate messaging;

    public CollaborationBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void presenceChanged(Long documentId, List<CollaborationEvent.Participant> participants) {
        send(documentId, CollaborationEvent.presence(documentId, participants));
    }

    public void cursorMoved(Long documentId, String actor, CollaborationEvent.Cursor cursor) {
        send(documentId, CollaborationEvent.cursor(documentId, actor, cursor));
    }

    public void annotationCreated(Long documentId, String actor, AnnotationResponse annotation) {
        send(documentId, CollaborationEvent.annotation(
            CollaborationEvent.Type.ANNOTATION_CREATED, documentId, actor, annotation));
    }

    public void annotationUpdated(Long documentId, String actor, AnnotationResponse annotation) {
        send(documentId, CollaborationEvent.annotation(
            CollaborationEvent.Type.ANNOTATION_UPDATED, documentId, actor, annotation));
    }

    public void annotationResolved(Long documentId, String actor, AnnotationResponse annotation) {
        send(documentId, CollaborationEvent.annotation(
            CollaborationEvent.Type.ANNOTATION_RESOLVED, documentId, actor, annotation));
    }

    public void annotationDeleted(Long documentId, String actor, Long annotationId) {
        send(documentId, CollaborationEvent.annotationDeleted(documentId, actor, annotationId));
    }

    public void replyAdded(Long documentId, String actor, CollaborationEvent.ReplyPayload reply) {
        send(documentId, CollaborationEvent.replyAdded(documentId, actor, reply));
    }

    public void versionCommitted(Long documentId, String actor, Integer version, String summary) {
        send(documentId, CollaborationEvent.versionCommitted(documentId, actor, version, summary));
    }

    private void send(Long documentId, CollaborationEvent event) {
        if (documentId == null) return;
        try {
            messaging.convertAndSend(DOCUMENT_TOPIC + documentId, event);
        } catch (Exception e) {
            // Never propagate: the work the event describes already succeeded.
            log.warn("Could not broadcast {} for document {}: {}",
                     event.type(), documentId, e.getMessage());
        }
    }
}
