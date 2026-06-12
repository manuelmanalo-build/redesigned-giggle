package com.realtimetradeprocessing.simulator.api;

import java.time.Instant;

import com.realtimetradeprocessing.simulator.persistence.entity.AccountEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.AccountStatus;

public record AccountResponse(
    String accountId,
    String displayName,
    AccountStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public static AccountResponse fromEntity(AccountEntity account) {
        return new AccountResponse(
            account.getId(),
            account.getDisplayName(),
            account.getStatus(),
            account.getCreatedAt(),
            account.getUpdatedAt()
        );
    }
}
