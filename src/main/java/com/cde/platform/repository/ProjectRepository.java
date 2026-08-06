package com.cde.platform.repository;

import com.cde.platform.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwner_Id(Long ownerId);

    @Query("SELECT p FROM Project p WHERE p.name LIKE %:query% OR p.description LIKE %:query%")
    List<Project> search(String query);
}
