package com.realtimetradeprocessing.simulator.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.realtimetradeprocessing.simulator.application.ReferenceDataApplicationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class ReferenceDataController {

    private final ReferenceDataApplicationService referenceDataApplicationService;

    public ReferenceDataController(ReferenceDataApplicationService referenceDataApplicationService) {
        this.referenceDataApplicationService = referenceDataApplicationService;
    }

    @GetMapping("/accounts")
    List<AccountResponse> getAccounts() {
        return referenceDataApplicationService.getAccounts();
    }

    @GetMapping("/accounts/{accountId}")
    AccountResponse getAccount(@PathVariable String accountId) {
        return referenceDataApplicationService.getAccount(accountId);
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return referenceDataApplicationService.createAccount(request);
    }

    @PutMapping("/accounts/{accountId}")
    AccountResponse updateAccount(
        @PathVariable String accountId,
        @Valid @RequestBody UpdateAccountRequest request
    ) {
        return referenceDataApplicationService.updateAccount(accountId, request);
    }

    @GetMapping("/instruments")
    List<InstrumentResponse> getInstruments() {
        return referenceDataApplicationService.getInstruments();
    }

    @GetMapping("/instruments/{symbol}")
    InstrumentResponse getInstrument(@PathVariable String symbol) {
        return referenceDataApplicationService.getInstrument(symbol);
    }

    @PostMapping("/instruments")
    @ResponseStatus(HttpStatus.CREATED)
    InstrumentResponse createInstrument(@Valid @RequestBody CreateInstrumentRequest request) {
        return referenceDataApplicationService.createInstrument(request);
    }

    @PutMapping("/instruments/{symbol}")
    InstrumentResponse updateInstrument(
        @PathVariable String symbol,
        @Valid @RequestBody UpdateInstrumentRequest request
    ) {
        return referenceDataApplicationService.updateInstrument(symbol, request);
    }
}
