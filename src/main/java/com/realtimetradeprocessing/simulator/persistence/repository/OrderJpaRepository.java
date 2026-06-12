package com.realtimetradeprocessing.simulator.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;

import jakarta.persistence.LockModeType;

public interface OrderJpaRepository extends JpaRepository<OrderEntity, String>, JpaSpecificationExecutor<OrderEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orderEntity from OrderEntity orderEntity where orderEntity.id = :orderId")
    Optional<OrderEntity> findByIdForUpdate(@Param("orderId") String orderId);
}
