package com.realtimetradeprocessing.simulator.api;

import com.realtimetradeprocessing.simulator.persistence.entity.AccountStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to create account reference data.")
public record CreateAccountRequest(
    @Schema(example = "ACC-100")
    @NotBlank @Size(max = 128) String accountId,
    @Schema(example = "Interview Demo Account")
    @NotBlank @Size(max = 255) String displayName,
    @Schema(example = "ACTIVE")
    @NotNull AccountStatus status
) {
}
