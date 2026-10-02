package com.dwurdy.straja.adapter.out.persistence;

import java.util.Set;

/** Binds a KeyValueStore onto the SavedData CompoundTag payload. */
public final class NbtStore implements KeyValueStore {
    private final JsonStore store;

    public NbtStore(JsonStore store) {
        this.store = store;
    }

    @Override public String get(String key) {
        // CompoundTag.getString returns "" for absent keys; the chunked
        // payload contract (getChunked) requires null to fall through to
        // the part-list read, so presence must be checked explicitly.
        return store.data().contains(key) ? store.data().getString(key) : null;
    }

    @Override public void put(String key, String value) {
        store.edit().putString(key, value);
    }

    @Override public void remove(String key) {
        store.edit().remove(key);
    }

    @Override public Set<String> keys() {
        return store.data().getAllKeys();
    }
}
