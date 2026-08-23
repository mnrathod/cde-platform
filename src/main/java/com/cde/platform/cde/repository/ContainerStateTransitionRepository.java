package com.cde.platform.cde.repository;

import com.cde.platform.cde.model.ContainerStateTransition;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContainerStateTransitionRepository
        extends JpaRepository<ContainerStateTransition, Long> {

    /**
     * The actor is joined rather than fetched per row: a transition history is
     * read as a whole, and every entry names who performed it.
     */
    @EntityGraph(attributePaths = "performedBy")
    List<ContainerStateTransition> findByRevisionIdOrderByPerformedAtAsc(Long revisionId);

    /**
     * The most recently recorded transition for a revision.
     *
     * <p>Ordered by id rather than by {@code performedAt}: the timestamp comes
     * from the clock and two transitions recorded in the same millisecond tie,
     * at which point "the latest" is whichever the database felt like
     * returning. The identity sequence is monotonic and does not tie.
     */
    @EntityGraph(attributePaths = "performedBy")
    Optional<ContainerStateTransition> findFirstByRevisionIdOrderByIdDesc(Long revisionId);
}
