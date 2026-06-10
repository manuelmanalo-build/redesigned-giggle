package com.realtimetradeprocessing.simulator.fix;

import java.util.Map;
import java.util.Optional;

public record SimplifiedFixMessage(Map<String, String> tags) {

    public SimplifiedFixMessage {
        tags = Map.copyOf(tags);
    }

    public Optional<String> value(String tag) {
        return Optional.ofNullable(tags.get(tag));
    }

    public String requiredValue(String tag) {
        return value(tag)
            .filter(value -> !value.isBlank())
            .map(String::trim)
            .orElseThrow(() -> new SimplifiedFixMappingException("Missing required FIX tag " + tag));
    }
}
