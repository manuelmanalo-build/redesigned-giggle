package com.realtimetradeprocessing.simulator.fix;

import java.util.LinkedHashMap;
import java.util.Map;

public class SimplifiedFixParser {

    public SimplifiedFixMessage parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new SimplifiedFixParseException("FIX message must not be blank");
        }

        Map<String, String> tags = new LinkedHashMap<>();
        String[] fields = rawMessage.trim().split("[|\u0001]");
        for (String field : fields) {
            if (field.isBlank()) {
                continue;
            }
            int separatorIndex = field.indexOf('=');
            if (separatorIndex <= 0 || separatorIndex == field.length() - 1) {
                throw new SimplifiedFixParseException("Invalid FIX field: " + field);
            }

            String tag = field.substring(0, separatorIndex).trim();
            String value = field.substring(separatorIndex + 1).trim();
            if (tag.isBlank() || value.isBlank()) {
                throw new SimplifiedFixParseException("Invalid FIX field: " + field);
            }
            if (tags.putIfAbsent(tag, value) != null) {
                throw new SimplifiedFixParseException("Duplicate FIX tag: " + tag);
            }
        }

        if (tags.isEmpty()) {
            throw new SimplifiedFixParseException("FIX message must contain at least one field");
        }
        return new SimplifiedFixMessage(tags);
    }
}
