package com.dwurdy.straja.adapter.in.item;

import com.dwurdy.straja.application.port.out.ItemView;

/**
 * Physical admin-tool surface: Minecraft-free item routing. Every item id here
 * is a recognized admin tool; the holder gate and all mutations live in the
 * application service, so a stolen item id alone never grants anything.
 */
public final class AdminToolSurface {
    public enum Tool {
        NONE, NPC_WAND, PATROL_WAND, SURVEY_ROD, NPC_CLONER, PRISON_MARKER, ROOM_MARKER
    }

    private AdminToolSurface() {}

    public static Tool tool(ItemView item) {
        if (item == null || item.isEmpty()) return Tool.NONE;
        return switch (item.id()) {
            case "straja:npc_wand" -> Tool.NPC_WAND;
            case "straja:patrol_wand" -> Tool.PATROL_WAND;
            case "straja:survey_rod" -> Tool.SURVEY_ROD;
            case "straja:npc_cloner" -> Tool.NPC_CLONER;
            case "straja:prison_marker" -> Tool.PRISON_MARKER;
            case "straja:room_marker" -> Tool.ROOM_MARKER;
            default -> Tool.NONE;
        };
    }
}
