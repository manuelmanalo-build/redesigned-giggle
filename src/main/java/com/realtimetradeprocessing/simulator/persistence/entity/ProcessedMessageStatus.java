package com.realtimetradeprocessing.simulator.persistence.entity;

public enum ProcessedMessageStatus {
    RECEIVED,
    PROCESSED,
    FAILED,
    DUPLICATE,
    DEAD_LETTERED
}
