package com.realtimetradeprocessing.simulator.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;

public interface ExecutionReportJpaRepository extends JpaRepository<ExecutionReportEntity, String> {

    List<ExecutionReportEntity> findByOrderIdOrderByCreatedAtAsc(String orderId);
}

