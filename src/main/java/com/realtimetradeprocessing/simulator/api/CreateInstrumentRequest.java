package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;

import com.realtimetradeprocessing.simulator.persistence.entity.AssetClass;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentStatus;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to create instrument reference data.")
public record CreateInstrumentRequest(
    @Schema(example = "IBM")
    @NotBlank @Size(max = 32) String symbol,
    @Schema(example = "International Business Machines Corporation")
    @NotBlank @Size(max = 255) String name,
    @Schema(example = "EQUITY")
    @NotNull AssetClass assetClass,
    @Schema(example = "ACTIVE")
    @NotNull InstrumentStatus status,
    @Schema(example = "0.01")
    @DecimalMin(value = "0.0", inclusive = false) BigDecimal tickSize
) {
}
