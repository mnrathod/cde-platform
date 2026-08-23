package com.cde.platform.cde.repository;

import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.cde.model.ContainerRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContainerRevisionRepository extends JpaRepository<ContainerRevision, Long> {

    List<ContainerRevision> findByContainerIdOrderByCreatedAtAsc(Long containerId);

    List<ContainerRevision> findByContainerIdAndState(Long containerId, ContainerState state);

    Optional<ContainerRevision> findByContainerIdAndStateAndSupersededByIsNull(
        Long containerId, ContainerState state);

    Optional<ContainerRevision> findByContainerIdAndRevisionCode(Long containerId, String revisionCode);
}
