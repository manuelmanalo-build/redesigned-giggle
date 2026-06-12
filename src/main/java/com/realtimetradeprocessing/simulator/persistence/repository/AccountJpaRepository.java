package com.realtimetradeprocessing.simulator.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.realtimetradeprocessing.simulator.persistence.entity.AccountEntity;

public interface AccountJpaRepository extends JpaRepository<AccountEntity, String> {

    List<AccountEntity> findAllByOrderByIdAsc();
}
