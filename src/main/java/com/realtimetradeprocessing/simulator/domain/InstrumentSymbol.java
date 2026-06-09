package com.realtimetradeprocessing.simulator.domain;

import java.util.Locale;

public record InstrumentSymbol(String value) {

    public InstrumentSymbol {
        value = Text.requireNonBlank(value, "Instrument symbol must not be blank").toUpperCase(Locale.ROOT);
    }

    public static InstrumentSymbol of(String value) {
        return new InstrumentSymbol(value);
    }
}

