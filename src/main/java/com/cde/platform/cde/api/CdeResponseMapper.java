package com.cde.platform.cde.api;

import com.cde.platform.cde.model.ContainerRevision;
import com.cde.platform.cde.model.ContainerStateTransition;
import com.cde.platform.cde.model.InformationContainer;
import com.cde.platform.cde.model.SuitabilityCode;
import com.cde.platform.model.User;

/**
 * Turns the stored entities into the shapes the API publishes.
 *
 * <p>One place, because the same revision is rendered by four endpoints and a
 * field that appears in three of them is worse than one that appears in none —
 * a client cannot tell whether the absence means "not set" or "this endpoint
 * does not send it".
 *
 * <p>Associations are read here rather than in the controllers, which is why
 * the repository methods behind them fetch with an entity graph: every accessor
 * below would otherwise be a query per row.
 */
final class CdeResponseMapper {

    private CdeResponseMapper() {
    }

    static ContainerDtos.ContainerResponse toResponse(InformationContainer container) {
        return new ContainerDtos.ContainerResponse(
            container.getId(),
            container.getProject() != null ? container.getProject().getId() : null,
            container.getContainerReference(),
            container.getNamingFields(),
            usernameOf(container.getCreatedBy()),
            container.getCreatedAt());
    }

    static RevisionDtos.RevisionResponse toResponse(ContainerRevision revision) {
        return new RevisionDtos.RevisionResponse(
            revision.getId(),
            revision.getContainer() != null ? revision.getContainer().getId() : null,
            revision.getRevisionCode(),
            revision.getState(),
            revision.getSuitabilityCode() != null ? revision.getSuitabilityCode().getCode() : null,
            revision.getDocument() != null ? revision.getDocument().getId() : null,
            usernameOf(revision.getCreatedBy()),
            revision.getCreatedAt(),
            usernameOf(revision.getPublishedBy()),
            revision.getPublishedAt(),
            revision.getApprovalReason(),
            revisionCodeOf(revision.getSupersedes()),
            revisionCodeOf(revision.getSupersededBy()),
            revision.isCurrentPublished());
    }

    static RevisionDtos.ContainerTransitionResponse toResponse(ContainerStateTransition transition) {
        return new RevisionDtos.ContainerTransitionResponse(
            transition.getId(),
            transition.getFromState(),
            transition.getToState(),
            usernameOf(transition.getPerformedBy()),
            transition.getPerformedAt(),
            transition.getReason());
    }

    static SuitabilityCodeDtos.SuitabilityCodeResponse toResponse(SuitabilityCode code) {
        return new SuitabilityCodeDtos.SuitabilityCodeResponse(
            code.getId(),
            code.getProject() != null ? code.getProject().getId() : null,
            code.getCode(),
            code.getDescription(),
            code.getDisplayOrder(),
            code.getValidInState(),
            code.isActive());
    }

    /**
     * The username, never the user id or the email. A transition history is
     * read by everyone on a project, and it needs to say who acted without
     * republishing an address to all of them.
     */
    private static String usernameOf(User user) {
        return user != null ? user.getUsername() : null;
    }

    private static String revisionCodeOf(ContainerRevision revision) {
        return revision != null ? revision.getRevisionCode() : null;
    }
}
