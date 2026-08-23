package com.cde.platform.cde.repository;

import com.cde.platform.cde.model.InformationContainer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InformationContainerRepository extends JpaRepository<InformationContainer, Long> {

    // No tenant predicate anywhere in this interface, deliberately. Row-Level
    // Security scopes every one of these, so a method added later without one
    // is still safe — which is the property a manual filter cannot give.
    List<InformationContainer> findByProjectId(Long projectId);

    Optional<InformationContainer> findByContainerReference(String containerReference);
}
