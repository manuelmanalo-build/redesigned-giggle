package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;

import com.realtimetradeprocessing.simulator.persistence.entity.AssetClass;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentStatus;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInstrumentRequest(
    @NotBlank @Size(max = 32) String symbol,
    @NotBlank @Size(max = 255) String name,
    @NotNull AssetClass assetClass,
    @NotNull InstrumentStatus status,
    @DecimalMin(value = "0.0", inclusive = false) BigDecimal tickSize
) {
}
