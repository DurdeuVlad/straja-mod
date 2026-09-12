package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A typed event emitted by domain services; adapters translate to messages/effects. */
public record DomainEvent(String type, Map<String, Object> data) {
    public static DomainEvent of(String type) {
        return new DomainEvent(type, Map.of());
    }

    public static DomainEvent of(String type, Map<String, Object> data) {
        return new DomainEvent(type, new LinkedHashMap<>(data));
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final String type;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(String type) {
            this.type = type;
        }

        public Builder put(String key, Object value) {
            data.put(key, value);
            return this;
        }

        public DomainEvent build() {
            return new DomainEvent(type, data);
        }
    }
}
