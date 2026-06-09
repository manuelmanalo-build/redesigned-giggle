package com.realtimetradeprocessing.simulator.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;

public interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {
}

