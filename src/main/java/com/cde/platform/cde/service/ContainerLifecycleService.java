package com.cde.platform.cde.service;

import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.cde.domain.StateTransitionNotPermittedException;
import com.cde.platform.cde.model.ContainerRevision;
import com.cde.platform.cde.model.ContainerStateTransition;
import com.cde.platform.cde.model.InformationContainer;
import com.cde.platform.cde.repository.ContainerRevisionRepository;
import com.cde.platform.cde.repository.ContainerStateTransitionRepository;
import com.cde.platform.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The only way a container revision changes state.
 *
 * <p>Each transition is a named domain operation rather than a setter, because
 * each one means something different and carries different obligations:
 * sharing needs a checker, publishing needs a recorded authorisation, rejecting
 * needs a reason the author can act on. A single {@code setState} would collapse
 * all of that into a value assignment and lose the distinction that makes the
 * CDE worth having.
 *
 * <p>Every operation records a transition row in the same transaction as the
 * change. Not afterwards, and not on a best-effort listener: an audit trail
 * that can be absent for a change that succeeded is not an audit trail.
 */
@Service
public class ContainerLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLifecycleService.class);

    private final ContainerRevisionRepository revisionRepository;
    private final ContainerStateTransitionRepository transitionRepository;

    public ContainerLifecycleService(ContainerRevisionRepository revisionRepository,
                                     ContainerStateTransitionRepository transitionRepository) {
        this.revisionRepository = revisionRepository;
        this.transitionRepository = transitionRepository;
    }

    /**
     * Creates the first revision of a container, in work in progress.
     */
    @Transactional
    public ContainerRevision startWorkInProgress(InformationContainer container,
                                                 String revisionCode,
                                                 User author) {
        ContainerRevision revision = revisionRepository.save(ContainerRevision.builder()
            .container(container)
            .revisionCode(revisionCode)
            .state(ContainerState.WORK_IN_PROGRESS)
            .createdBy(author)
            .build());

        recordTransition(revision, ContainerState.WORK_IN_PROGRESS, ContainerState.WORK_IN_PROGRESS,
                         author, "Created");
        return revision;
    }

    /**
     * Issues a revision for coordination by other task teams.
     *
     * <p>Sharing is not approval. The distinction is load-bearing: information
     * issued for coordination that is mistaken for information approved for use
     * is how unapproved design ends up built.
     */
    @Transactional
    public ContainerRevision share(ContainerRevision revision, User checker, String reason) {
        return transition(revision, ContainerState.SHARED, checker, reason);
    }

    /**
     * Returns a shared revision to its author for rework.
     */
    @Transactional
    public ContainerRevision reject(ContainerRevision revision, User reviewer, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "A rejection must carry a reason — the author needs to know what to change.");
        }
        return transition(revision, ContainerState.WORK_IN_PROGRESS, reviewer, reason);
    }

    /**
     * Authorises a revision for use. From here the content is frozen.
     *
     * @param approvalReason the authorisation record. Required: a published
     *                       revision is the contractual record, and a database
     *                       CHECK constraint refuses one without an approver.
     */
    @Transactional
    public ContainerRevision publish(ContainerRevision revision, User approver, String approvalReason) {
        if (approver == null) {
            throw new IllegalArgumentException("Publication requires a recorded approver.");
        }
        requireTransitionPermitted(revision.getState(), ContainerState.PUBLISHED);

        revision.setPublishedBy(approver);
        revision.setPublishedAt(LocalDateTime.now());
        revision.setApprovalReason(approvalReason);

        return transition(revision, ContainerState.PUBLISHED, approver, approvalReason);
    }

    /**
     * Issues a new revision that replaces the current published one.
     *
     * <p>This is the only way a published container changes. The previous
     * revision is not edited and not removed — it is archived, and remains
     * retrievable for as long as the asset exists. Lineage is written in both
     * directions so it can be walked from either end.
     */
    @Transactional
    public ContainerRevision supersede(ContainerRevision publishedRevision,
                                       String newRevisionCode,
                                       User author) {
        if (publishedRevision.getState() != ContainerState.PUBLISHED) {
            throw new StateTransitionNotPermittedException(
                publishedRevision.getState(), ContainerState.ARCHIVED);
        }
        if (publishedRevision.getSupersededBy() != null) {
            throw new IllegalStateException(
                "Revision " + publishedRevision.getRevisionCode()
                + " has already been superseded by " + publishedRevision.getSupersededBy().getRevisionCode());
        }

        ContainerRevision replacement = revisionRepository.save(ContainerRevision.builder()
            .container(publishedRevision.getContainer())
            .revisionCode(newRevisionCode)
            .state(ContainerState.WORK_IN_PROGRESS)
            .supersedes(publishedRevision)
            .document(publishedRevision.getDocument())
            .filePath(publishedRevision.getFilePath())
            .createdBy(author)
            .build());

        recordTransition(replacement, ContainerState.WORK_IN_PROGRESS, ContainerState.WORK_IN_PROGRESS,
                         author, "Supersedes " + publishedRevision.getRevisionCode());

        // The back-reference and the archive happen together. The trigger
        // permits exactly these two changes on a published row and refuses
        // every other one.
        publishedRevision.setSupersededBy(replacement);
        transition(publishedRevision, ContainerState.ARCHIVED, author,
                   "Superseded by " + newRevisionCode);

        log.info("Container {} revision {} superseded by {}",
                 publishedRevision.getContainer().getId(),
                 publishedRevision.getRevisionCode(), newRevisionCode);

        return replacement;
    }

    /**
     * Archives a revision that was never published — abandoned work in progress,
     * or a shared revision that will not proceed.
     */
    @Transactional
    public ContainerRevision archive(ContainerRevision revision, User actor, String reason) {
        return transition(revision, ContainerState.ARCHIVED, actor, reason);
    }

    @Transactional(readOnly = true)
    public List<ContainerStateTransition> historyOf(ContainerRevision revision) {
        return transitionRepository.findByRevisionIdOrderByPerformedAtAsc(revision.getId());
    }

    private ContainerRevision transition(ContainerRevision revision,
                                         ContainerState target,
                                         User actor,
                                         String reason) {
        ContainerState current = revision.getState();
        requireTransitionPermitted(current, target);

        revision.setState(target);
        ContainerRevision saved = revisionRepository.save(revision);
        recordTransition(saved, current, target, actor, reason);

        // The actor and the states are business events worth logging; the
        // reason is free text that may quote document content, so it is not.
        log.info("Container revision {} moved {} -> {} by user {}",
                 saved.getId(), current, target, actor.getId());
        return saved;
    }

    private void requireTransitionPermitted(ContainerState from, ContainerState to) {
        if (!from.canTransitionTo(to)) {
            throw new StateTransitionNotPermittedException(from, to);
        }
    }

    private void recordTransition(ContainerRevision revision, ContainerState from,
                                  ContainerState to, User actor, String reason) {
        transitionRepository.save(ContainerStateTransition.builder()
            .revision(revision)
            .fromState(from)
            .toState(to)
            .performedBy(actor)
            .reason(reason)
            .build());
    }
}
