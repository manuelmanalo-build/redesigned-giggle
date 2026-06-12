package com.realtimetradeprocessing.simulator.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;

public interface ExecutionReportJpaRepository extends JpaRepository<ExecutionReportEntity, String>, JpaSpecificationExecutor<ExecutionReportEntity> {

    List<ExecutionReportEntity> findByOrderIdOrderByCreatedAtAsc(String orderId);
}
