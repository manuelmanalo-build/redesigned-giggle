package com.realtimetradeprocessing.simulator.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;

public interface TradeJpaRepository extends JpaRepository<TradeEntity, String> {

    List<TradeEntity> findByOrderIdOrderByCreatedAtAsc(String orderId);
}

