package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.persistence.entity.AssetClass;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.InstrumentStatus;

public record InstrumentResponse(
    String symbol,
    String name,
    AssetClass assetClass,
    InstrumentStatus status,
    BigDecimal tickSize,
    Instant createdAt,
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
