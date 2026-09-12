package com.dwurdy.straja.adapter.out.persistence;

import java.util.Set;

/**
 * Minimal string-key/string-value store. Platform-free so repository
 * implementations can be unit-tested without a Minecraft runtime; the NBT
 * binding lives in {@link NbtStore}.
 */
public interface KeyValueStore {
    String get(String key);

    void put(String key, String value);

    void remove(String key);

    Set<String> keys();
}
