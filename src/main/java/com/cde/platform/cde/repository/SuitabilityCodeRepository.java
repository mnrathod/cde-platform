package com.cde.platform.cde.repository;

import com.cde.platform.cde.model.SuitabilityCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuitabilityCodeRepository extends JpaRepository<SuitabilityCode, Long> {

    List<SuitabilityCode> findByProjectIdAndActiveTrueOrderByDisplayOrderAsc(Long projectId);

    boolean existsByProjectIdAndCode(Long projectId, String code);
}
