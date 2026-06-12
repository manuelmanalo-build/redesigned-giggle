package com.realtimetradeprocessing.simulator.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "instruments")
public class InstrumentEntity {

    @Id
    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 32)
    private AssetClass assetClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InstrumentStatus status;

    @Column(name = "tick_size", precision = 19, scale = 8)
    private BigDecimal tickSize;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InstrumentEntity() {
    }

    public InstrumentEntity(
        String symbol,
        String name,
        AssetClass assetClass,
        InstrumentStatus status,
        BigDecimal tickSize,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.symbol = symbol;
        this.name = name;
        this.assetClass = assetClass;
        this.status = status;
        this.tickSize = tickSize;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public boolean isActive() {
        return status == InstrumentStatus.ACTIVE;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public AssetClass getAssetClass() {
        return assetClass;
    }

    public InstrumentStatus getStatus() {
        return status;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
