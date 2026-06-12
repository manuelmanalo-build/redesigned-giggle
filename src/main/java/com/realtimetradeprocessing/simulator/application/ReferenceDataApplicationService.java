package com.realtimetradeprocessing.simulator.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.realtimetradeprocessing.simulator.api.AccountResponse;
import com.realtimetradeprocessing.simulator.api.CreateAccountRequest;
import com.realtimetradeprocessing.simulator.api.CreateInstrumentRequest;
import com.realtimetradeprocessing.simulator.api.InstrumentResponse;
import com.realtimetradeprocessing.simulator.api.ResourceConflictException;
import com.realtimetradeprocessing.simulator.api.ResourceNotFoundException;
import com.realtimetradeprocessing.simulator.api.UpdateAccountRequest;
import com.realtimetradeprocessing.simulator.api.UpdateInstrumentRequest;
import com.realtimetradeprocessing.simulator.persistence.entity.AccountEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.AccountJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.InstrumentJpaRepository;

@Service
public class ReferenceDataApplicationService {

    private final AccountJpaRepository accountRepository;
    private final InstrumentJpaRepository instrumentRepository;
    private final Clock clock;

    public ReferenceDataApplicationService(
        AccountJpaRepository accountRepository,
        InstrumentJpaRepository instrumentRepository
    ) {
        this.accountRepository = accountRepository;
        this.instrumentRepository = instrumentRepository;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccounts() {
        return accountRepository.findAllByOrderByIdAsc()
            .stream()
            .map(AccountResponse::fromEntity)
            .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        return accountRepository.findById(accountId)
            .map(AccountResponse::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        String accountId = request.accountId().trim();
        if (accountRepository.existsById(accountId)) {
            throw new ResourceConflictException("Account already exists: " + accountId);
        }
        Instant now = clock.instant();
        AccountEntity account = new AccountEntity(
            accountId,
            request.displayName().trim(),
            request.status(),
            now,
            now
        );
        return AccountResponse.fromEntity(accountRepository.saveAndFlush(account));
    }

    @Transactional
    public AccountResponse updateAccount(String accountId, UpdateAccountRequest request) {
        AccountEntity existing = accountRepository.findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        AccountEntity updated = new AccountEntity(
            existing.getId(),
            request.displayName().trim(),
            request.status(),
            existing.getCreatedAt(),
            clock.instant()
        );
        return AccountResponse.fromEntity(accountRepository.saveAndFlush(updated));
    }

    @Transactional(readOnly = true)
    public List<InstrumentResponse> getInstruments() {
        return instrumentRepository.findAllByOrderBySymbolAsc()
            .stream()
            .map(InstrumentResponse::fromEntity)
            .toList();
    }

    @Transactional(readOnly = true)
    public InstrumentResponse getInstrument(String symbol) {
        return instrumentRepository.findById(symbol)
            .map(InstrumentResponse::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("Instrument not found: " + symbol));
    }

    @Transactional
    public InstrumentResponse createInstrument(CreateInstrumentRequest request) {
        String symbol = request.symbol().trim().toUpperCase();
        if (instrumentRepository.existsById(symbol)) {
            throw new ResourceConflictException("Instrument already exists: " + symbol);
        }
        Instant now = clock.instant();
        InstrumentEntity instrument = new InstrumentEntity(
            symbol,
            request.name().trim(),
            request.assetClass(),
            request.status(),
            request.tickSize(),
            now,
            now
        );
        return InstrumentResponse.fromEntity(instrumentRepository.saveAndFlush(instrument));
    }

    @Transactional
    public InstrumentResponse updateInstrument(String symbol, UpdateInstrumentRequest request) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        InstrumentEntity existing = instrumentRepository.findById(normalizedSymbol)
            .orElseThrow(() -> new ResourceNotFoundException("Instrument not found: " + normalizedSymbol));
        InstrumentEntity updated = new InstrumentEntity(
            existing.getSymbol(),
            request.name().trim(),
            request.assetClass(),
            request.status(),
            request.tickSize(),
            existing.getCreatedAt(),
            clock.instant()
        );
        return InstrumentResponse.fromEntity(instrumentRepository.saveAndFlush(updated));
    }
}
