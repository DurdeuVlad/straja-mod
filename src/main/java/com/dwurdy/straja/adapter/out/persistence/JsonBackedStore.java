package com.dwurdy.straja.adapter.out.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared serialization for aggregate stores. The payload is a JSON string
 * under the "json" key; a parse failure copies the raw payload into
 * "corrupt_backup" and returns a fresh default — never silently trusts bad
 * data and never grants access from it.
 */
public abstract class JsonBackedStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Straja");
    protected static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final StoreAccess access;
    private final String storeName;

    protected JsonBackedStore(StoreAccess access, String storeName) {
        this.access = access;
        this.storeName = storeName;
    }

    protected KeyValueStore store() {
        return access.store(storeName);
    }

    protected <T> T readJson(Class<T> type, Supplier<T> fallback) {
        String raw = store().get("json");
        if (raw == null || raw.isEmpty()) return fallback.get();
        try {
            T value = GSON.fromJson(JsonParser.parseString(raw), type);
            return value != null ? value : fallback.get();
        } catch (RuntimeException error) {
            LOGGER.error("[Straja] corrupt store '{}' — backing up raw payload and starting fresh", storeName);
            store().put("corrupt_backup", raw);
            store().remove("json");
            return fallback.get();
        }
    }

    protected void writeJson(Object value) {
        store().put("json", GSON.toJson(value));
    }
}
