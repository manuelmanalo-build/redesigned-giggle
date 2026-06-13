package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.persistence.entity.AssetClass;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Instrument reference-data response.")
public record InstrumentResponse(
    @Schema(example = "AAPL")
    String symbol,
    @Schema(example = "Apple Inc.")
    String name,
    @Schema(example = "EQUITY")
    AssetClass assetClass,
    @Schema(example = "ACTIVE")
    InstrumentStatus status,
    @Schema(example = "0.01", nullable = true)
    BigDecimal tickSize,
    @Schema(example = "2026-06-01T00:00:00Z")
    Instant createdAt,
    @Schema(example = "2026-06-01T00:00:00Z")
    Instant updatedAt
) {

    public static InstrumentResponse fromEntity(InstrumentEntity instrument) {
        return new InstrumentResponse(
            instrument.getSymbol(),
            instrument.getName(),
            instrument.getAssetClass(),
            instrument.getStatus(),
            instrument.getTickSize(),
            instrument.getCreatedAt(),
            instrument.getUpdatedAt()
        );
    }
}
