package com.realtimetradeprocessing.simulator.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentEntity;

public interface InstrumentJpaRepository extends JpaRepository<InstrumentEntity, String> {

    List<InstrumentEntity> findAllByOrderBySymbolAsc();
}
