package com.cde.platform.cde.repository;

import com.cde.platform.cde.model.ContainerStateTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContainerStateTransitionRepository
        extends JpaRepository<ContainerStateTransition, Long> {

    List<ContainerStateTransition> findByRevisionIdOrderByPerformedAtAsc(Long revisionId);
}
