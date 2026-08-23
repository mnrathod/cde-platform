package com.cde.platform.cde.repository;

import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.cde.model.ContainerRevision;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContainerRevisionRepository extends JpaRepository<ContainerRevision, Long> {

    /**
     * Fetched with its associations, because rendering a revision reads all of
     * them — who made it, who approved it, what it replaced and what replaced
     * it. Left lazy, listing a container's revisions issued a query per
     * association per row.
     */
    @EntityGraph(attributePaths = {
        "createdBy", "publishedBy", "suitabilityCode", "supersedes", "supersededBy" })
    List<ContainerRevision> findByContainerIdOrderByCreatedAtAsc(Long containerId);

    List<ContainerRevision> findByContainerIdAndState(Long containerId, ContainerState state);

    Optional<ContainerRevision> findByContainerIdAndStateAndSupersededByIsNull(
        Long containerId, ContainerState state);

    Optional<ContainerRevision> findByContainerIdAndRevisionCode(Long containerId, String revisionCode);
}
