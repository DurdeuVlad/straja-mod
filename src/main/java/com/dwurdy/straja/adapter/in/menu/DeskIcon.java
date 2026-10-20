package com.dwurdy.straja.adapter.in.menu;

import java.util.List;

/**
 * One clickable desk-menu icon — a pure view value: the registry item id, the
 * Romanian button name and up to a few lore lines. Never throws; a missing or
 * blank item id degrades to {@link #FALLBACK_ITEM}.
 */
public record DeskIcon(String itemId, String name, List<String> lore) {
    /** Vanilla item used whenever an icon id is missing or unmapped. */
    public static final String FALLBACK_ITEM = "minecraft:paper";

    public DeskIcon {
        itemId = itemId == null || itemId.isBlank() ? FALLBACK_ITEM : itemId;
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }
}
