package com.dwurdy.straja.adapter.out.persistence;

/** Resolves the named stores. Production: SavedData/NBT; tests: in-memory maps. */
@FunctionalInterface
public interface StoreAccess {
    KeyValueStore store(String name);
}
