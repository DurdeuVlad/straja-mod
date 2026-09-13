package com.dwurdy.straja.adapter.in.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dwurdy.straja.adapter.in.item.PhysicalItemSurface;
import com.dwurdy.straja.application.port.out.ItemView;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhysicalItemSurfaceTest {
    @Test
    void routesOnlyTheBoundedPaperSurfaceItems() {
        assertEquals(PhysicalItemSurface.Action.MISSION_CARNET,
                PhysicalItemSurface.action(item("straja:order_book")));
        assertEquals(PhysicalItemSurface.Action.MISSION_CARNET,
                PhysicalItemSurface.action(item("straja:mission_carnet")));
        assertEquals(PhysicalItemSurface.Action.ARCHIVE_FOLDER,
                PhysicalItemSurface.action(item("straja:archive_folder")));
        assertEquals(PhysicalItemSurface.Action.ARCHIVE_DOCUMENT,
                PhysicalItemSurface.action(item("straja:archive_document")));
        assertEquals(PhysicalItemSurface.Action.FINE_BOOK,
                PhysicalItemSurface.action(item("straja:fine_book")));
        assertEquals(PhysicalItemSurface.Action.FINE_NOTICE,
                PhysicalItemSurface.action(item("straja:fine_notice")));
        assertEquals(PhysicalItemSurface.Action.ARCHIVE_TOOL,
                PhysicalItemSurface.action(item("straja:carbon_paper")));
        assertEquals(PhysicalItemSurface.Action.ARCHIVE_TOOL,
                PhysicalItemSurface.action(item("straja:archive_stamp")));
        assertEquals(PhysicalItemSurface.Action.ARCHIVE_TOOL,
                PhysicalItemSurface.action(item("straja:official_envelope")));
        assertEquals(PhysicalItemSurface.Action.TRAINING_MANUAL,
                PhysicalItemSurface.action(item("straja:training_manual")));
    }

    @Test
    void ignoresEmptyAndUnregisteredItems() {
        assertEquals(PhysicalItemSurface.Action.NONE,
                PhysicalItemSurface.action(ItemView.EMPTY));
        assertEquals(PhysicalItemSurface.Action.NONE,
                PhysicalItemSurface.action(item("straja:cuffs")));
    }

    private static ItemView item(String id) {
        return new ItemView(id, 1, 1, Map.of());
    }
}
