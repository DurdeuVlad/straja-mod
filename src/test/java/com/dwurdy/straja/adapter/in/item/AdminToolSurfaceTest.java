package com.dwurdy.straja.adapter.in.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dwurdy.straja.application.port.out.ItemView;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminToolSurfaceTest {
    @Test
    void routesEveryRegisteredAdminTool() {
        assertEquals(AdminToolSurface.Tool.NPC_WAND, AdminToolSurface.tool(item("straja:npc_wand")));
        assertEquals(AdminToolSurface.Tool.PATROL_WAND, AdminToolSurface.tool(item("straja:patrol_wand")));
        assertEquals(AdminToolSurface.Tool.SURVEY_ROD, AdminToolSurface.tool(item("straja:survey_rod")));
        assertEquals(AdminToolSurface.Tool.NPC_CLONER, AdminToolSurface.tool(item("straja:npc_cloner")));
        assertEquals(AdminToolSurface.Tool.PRISON_MARKER, AdminToolSurface.tool(item("straja:prison_marker")));
        assertEquals(AdminToolSurface.Tool.ROOM_MARKER, AdminToolSurface.tool(item("straja:room_marker")));
    }

    @Test
    void ignoresEmptyAndNonToolItems() {
        assertEquals(AdminToolSurface.Tool.NONE, AdminToolSurface.tool(ItemView.EMPTY));
        assertEquals(AdminToolSurface.Tool.NONE, AdminToolSurface.tool(item("straja:cuffs")));
        assertEquals(AdminToolSurface.Tool.NONE, AdminToolSurface.tool(item("straja:fine_book")));
    }

    private static ItemView item(String id) {
        return new ItemView(id, 1, 1, Map.of());
    }
}
