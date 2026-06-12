package com.realtimetradeprocessing.simulator.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.realtimetradeprocessing.simulator.domain.DomainException;
import com.realtimetradeprocessing.simulator.domain.Order;
import com.realtimetradeprocessing.simulator.persistence.entity.AccountEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.AccountJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.InstrumentJpaRepository;

@Service
public class ReferenceDataValidationService {

    private final AccountJpaRepository accountRepository;
    private final InstrumentJpaRepository instrumentRepository;

    public ReferenceDataValidationService(
        AccountJpaRepository accountRepository,
        InstrumentJpaRepository instrumentRepository
    ) {
        this.accountRepository = accountRepository;
        this.instrumentRepository = instrumentRepository;
    }

    @Transactional(readOnly = true)
    public void validateOrderReferenceData(Order order) {
        AccountEntity account = accountRepository.findById(order.accountId().value())
            .orElseThrow(() -> new DomainException("Unknown account: " + order.accountId().value()));
        if (!account.isActive()) {
            throw new DomainException("Account is not active: " + account.getId() + " (" + account.getStatus() + ")");
        }

        InstrumentEntity instrument = instrumentRepository.findById(order.symbol().value())
            .orElseThrow(() -> new DomainException("Unknown instrument: " + order.symbol().value()));
        if (!instrument.isActive()) {
            throw new DomainException("Instrument is not active: " + instrument.getSymbol() + " (" + instrument.getStatus() + ")");
        }
    }
}
