package com.dwurdy.straja.domain.model;

import java.util.Map;

/** An item to be created/delivered, with optional custom data and display name. */
public record ItemSpec(String id, int count, Map<String, String> customData, String displayName) {
    public static ItemSpec of(String id, int count) {
        return new ItemSpec(id, count, Map.of(), null);
    }

    public ItemSpec named(String name) {
        return new ItemSpec(id, count, customData, name);
    }

    public ItemSpec withData(String key, String value) {
        var next = new java.util.LinkedHashMap<>(customData);
        next.put(key, value);
        return new ItemSpec(id, count, Map.copyOf(next), displayName);
    }
}
