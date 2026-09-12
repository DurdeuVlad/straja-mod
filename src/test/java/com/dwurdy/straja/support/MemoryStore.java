package com.dwurdy.straja.support;

import com.dwurdy.straja.adapter.out.persistence.KeyValueStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** In-memory KeyValueStore for unit tests — no Minecraft classes. */
public final class MemoryStore implements KeyValueStore {
    private final Map<String, String> data = new LinkedHashMap<>();

    @Override public String get(String key) { return data.get(key); }
    @Override public void put(String key, String value) { data.put(key, value); }
    @Override public void remove(String key) { data.remove(key); }
    @Override public Set<String> keys() { return data.keySet(); }
    public Map<String, String> raw() { return data; }
}
