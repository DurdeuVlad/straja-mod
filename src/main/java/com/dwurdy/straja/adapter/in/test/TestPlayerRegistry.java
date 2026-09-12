package com.dwurdy.straja.adapter.in.test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registry of virtual test players, keyed by lowercase name. In-memory only —
 * virtual players are test scaffolding and never persist.
 */
public final class TestPlayerRegistry {
    private final Map<String, VirtualPlayerGateway> byName = new LinkedHashMap<>();

    public VirtualPlayerGateway create(String name) {
        return byName.computeIfAbsent(key(name), k -> new VirtualPlayerGateway(name));
    }

    public VirtualPlayerGateway get(String name) {
        return byName.get(key(name));
    }

    public VirtualPlayerGateway byUuid(UUID uuid) {
        for (var player : byName.values()) {
            if (player.uuid().equals(uuid)) return player;
        }
        return null;
    }

    public boolean remove(String name) {
        return byName.remove(key(name)) != null;
    }

    public Collection<VirtualPlayerGateway> all() {
        return byName.values();
    }

    public void clear() {
        byName.clear();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
