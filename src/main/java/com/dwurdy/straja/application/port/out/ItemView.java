package com.dwurdy.straja.application.port.out;

import java.util.Map;

/** Read-only view of an item stack, detached from the Minecraft runtime. */
public record ItemView(String id, int count, int maxStackSize, Map<String, String> customData) {
    public static final ItemView EMPTY = new ItemView("minecraft:air", 0, 64, Map.of());

    public boolean isEmpty() {
        return count <= 0 || "minecraft:air".equals(id);
    }

    public ItemView withCount(int next) {
        return new ItemView(id, next, maxStackSize, customData);
    }

    public String data(String key) {
        return customData.get(key);
    }
}
