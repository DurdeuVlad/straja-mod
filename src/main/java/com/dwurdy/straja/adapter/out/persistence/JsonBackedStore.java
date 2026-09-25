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

    // NBT StringTags persist through DataOutput.writeUTF — hard-capped at
    // 65535 modified-UTF-8 bytes. Aggregate stores can legitimately exceed
    // that (e.g. the fines ledger under sustained volume), so large payloads
    // are chunked across key_part_N entries; single-key storage is kept for
    // small payloads so existing saves read back unchanged.
    static final int CHUNK_BYTE_LIMIT = 32_000;

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
        String raw = getChunked("json");
        if (raw == null || raw.isEmpty()) return fallback.get();
        try {
            T value = GSON.fromJson(JsonParser.parseString(raw), type);
            return value != null ? value : fallback.get();
        } catch (RuntimeException error) {
            LOGGER.error("[Straja] corrupt store '{}' — backing up raw payload and starting fresh", storeName);
            putChunked("corrupt_backup", raw);
            removeChunked("json");
            return fallback.get();
        }
    }

    /** Reads a V2 aggregate and fails closed on an unknown future schema. */
    protected <T> T readJsonVersioned(Class<T> type, Supplier<T> fallback, int currentSchemaVersion) {
        T value = readJson(type, fallback);
        try {
            java.lang.reflect.Field field = type.getField("schemaVersion");
            int version = field.getInt(value);
            if (version > currentSchemaVersion) {
                LOGGER.error("[Straja] unsupported future schema {} for '{}' — backing up and resetting", version, storeName);
                putChunked("future_schema_backup", GSON.toJson(value));
                removeChunked("json");
                return fallback.get();
            }
            field.setInt(value, currentSchemaVersion);
        } catch (NoSuchFieldException ignored) {
            // V1/list stores do not carry schema metadata.
        } catch (ReflectiveOperationException error) {
            LOGGER.error("[Straja] unreadable schema metadata for '{}' — failing closed", storeName);
            return fallback.get();
        }
        return value;
    }

    protected void writeJson(Object value) {
        putChunked("json", GSON.toJson(value));
    }

    /** Reads a possibly-chunked value; absent keys and single-key values behave as before. */
    private String getChunked(String key) {
        String single = store().get(key);
        if (single != null) return single;
        String count = store().get(key + "_parts");
        if (count == null) return null;
        int n;
        try {
            n = Integer.parseInt(count);
        } catch (NumberFormatException error) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String part = store().get(key + "_part_" + i);
            if (part == null) break; // truncated — let the parse path handle it
            joined.append(part);
        }
        return joined.toString();
    }

    private void putChunked(String key, String value) {
        removeChunked(key);
        if (utf8Bytes(value) <= CHUNK_BYTE_LIMIT) {
            store().put(key, value);
            return;
        }
        java.util.List<String> parts = chunkUtf8(value);
        store().put(key + "_parts", String.valueOf(parts.size()));
        for (int i = 0; i < parts.size(); i++) {
            store().put(key + "_part_" + i, parts.get(i));
        }
    }

    private void removeChunked(String key) {
        store().remove(key);
        java.util.List<String> stale = new java.util.ArrayList<>();
        for (String k : store().keys()) {
            if (k.equals(key + "_parts") || k.startsWith(key + "_part_")) stale.add(k);
        }
        stale.forEach(store()::remove);
    }

    /** Splits on code-point boundaries so no chunk exceeds the writeUTF cap. */
    static java.util.List<String> chunkUtf8(String value) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            int width = modifiedUtf8Width(cp);
            if (bytes + width > CHUNK_BYTE_LIMIT && current.length() > 0) {
                parts.add(current.toString());
                current.setLength(0);
                bytes = 0;
            }
            current.appendCodePoint(cp);
            bytes += width;
            i += Character.charCount(cp);
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    private static int utf8Bytes(String value) {
        int total = 0;
        for (int i = 0; i < value.length(); i++) {
            int cp = value.codePointAt(i);
            total += modifiedUtf8Width(cp);
            i += Character.charCount(cp) - 1;
        }
        return total;
    }

    /** Byte width under Java's modified UTF-8 (writeUTF): supplementary
     *  code points encode as two 3-byte surrogate halves. */
    private static int modifiedUtf8Width(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 6;
    }
}
