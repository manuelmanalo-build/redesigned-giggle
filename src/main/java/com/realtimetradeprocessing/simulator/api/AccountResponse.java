package com.realtimetradeprocessing.simulator.api;

import java.time.Instant;

import com.realtimetradeprocessing.simulator.persistence.entity.AccountEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Account reference-data response.")
public record AccountResponse(
    @Schema(example = "ACC-001")
    String accountId,
    @Schema(example = "Demo Active Account")
    String displayName,
    @Schema(example = "ACTIVE")
    AccountStatus status,
    @Schema(example = "2026-06-01T00:00:00Z")
    Instant createdAt,
    @Schema(example = "2026-06-01T00:00:00Z")
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
