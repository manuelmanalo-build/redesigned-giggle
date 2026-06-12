package com.realtimetradeprocessing.simulator.api;

import com.realtimetradeprocessing.simulator.persistence.entity.AccountStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateAccountRequest(
    @NotBlank @Size(max = 255) String displayName,
    @NotNull AccountStatus status
) {
}
