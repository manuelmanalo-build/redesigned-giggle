package com.realtimetradeprocessing.simulator.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.realtimetradeprocessing.simulator.persistence.entity.IdempotencyRecordEntity;

public interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {
}

