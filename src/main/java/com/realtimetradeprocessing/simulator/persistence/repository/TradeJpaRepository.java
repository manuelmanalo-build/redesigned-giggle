package com.realtimetradeprocessing.simulator.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;

public interface TradeJpaRepository extends JpaRepository<TradeEntity, String>, JpaSpecificationExecutor<TradeEntity> {

    List<TradeEntity> findByOrderIdOrderByCreatedAtAsc(String orderId);
}
