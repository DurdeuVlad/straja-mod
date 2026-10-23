package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wire-codec pins for the desk GUI: the view round-trips through the codec,
 * action ids never cross the wire (the server resolves slots), and malformed
 * sizes fail closed instead of trusting the packet. Runs headless — the fake
 * buffer is a plain deque standing in for {@code FriendlyByteBuf}.
 */
class NpcDeskWireTest {

    /** In-memory {@link DeskWire.Buf}: writes queue, reads drain in order. */
    private static final class FakeBuf implements DeskWire.Buf {
        private final Deque<Object> cells = new ArrayDeque<>();
        private final Deque<Object> in;

        FakeBuf() { this.in = cells; }
        FakeBuf(Deque<Object> in) { this.in = in; }

        @Override public void writeUtf(String value, int maxLength) {
            if (value == null || value.length() > maxLength) {
                throw new IllegalArgumentException("utf out of bounds");
            }
            cells.add(value);
        }

        @Override public void writeVarInt(int value) { cells.add(value); }

        @Override public String readUtf(int maxLength) {
            String s = (String) in.poll();
            if (s == null || s.length() > maxLength) {
                throw new IllegalArgumentException("utf out of bounds");
            }
            return s;
        }

        @Override public int readVarInt() {
            Integer v = (Integer) in.poll();
            if (v == null) throw new IllegalArgumentException("missing varint");
            return v;
        }

        FakeBuf reader() { return new FakeBuf(new ArrayDeque<>(cells)); }
    }

    private DeskWire.View sampleView() {
        return new DeskWire.View("sess-abc", 3, "Ghișeu — Recepționer", 6,
                List.of(
                        new DeskWire.Tile(0, "straja:mission_carnet",
                                "Înscriere", List.of("Creează dosarul.",
                                        "Se execută imediat.")),
                        new DeskWire.Tile(45, "minecraft:arrow",
                                "« Înapoi", List.of("Revii la pagina anterioară."))));
    }

    @Test
    void viewRoundTripsThroughTheCodec() {
        DeskWire.View view = sampleView();
        var buf = new FakeBuf();
        DeskWire.write(buf, view);
        DeskWire.View back = DeskWire.read(buf.reader());
        assertEquals(view, back);
    }

    @Test
    void viewOfDropsActionIdsFromTheWire() {
        DeskPage page = new DeskPage("Ghișeu", 6, List.of(
                new DeskButton(0, new DeskIcon("straja:mission_carnet", "Înscriere",
                        List.of("Creează dosarul.")), "enroll:"),
                new DeskButton(4, new DeskIcon("minecraft:paper", "Informații",
                        List.of()), null)));
        DeskWire.View view = DeskWire.viewOf("sess", 0, page);
        assertEquals(2, view.tiles().size());
        assertEquals("Înscriere", view.tiles().get(0).name());
        assertEquals("straja:mission_carnet", view.tiles().get(0).itemId());
        // The tile record has no actionId field at all — nothing to leak.
    }

    @Test
    void missingIconFallsBackToPaper() {
        DeskPage page = new DeskPage("x", 6, List.of(
                new DeskButton(0, null, "a:b")));
        DeskWire.View view = DeskWire.viewOf("s", 0, page);
        assertEquals(DeskIcon.FALLBACK_ITEM, view.tiles().get(0).itemId());
        assertEquals("", view.tiles().get(0).name());
        assertEquals(List.of(), view.tiles().get(0).lore());
    }

    @Test
    void malformedCountsAreRejected() {
        // rows > MAX_ROWS
        var tooManyRows = new ArrayDeque<Object>(
                List.of("s", 0, "t", 9));
        assertThrows(IllegalArgumentException.class,
                () -> DeskWire.read(new FakeBuf(tooManyRows)));

        // tiles > MAX_BUTTONS
        var tooManyTiles = new ArrayDeque<Object>(
                List.of("s", 0, "t", 6, 60));
        assertThrows(IllegalArgumentException.class,
                () -> DeskWire.read(new FakeBuf(tooManyTiles)));

        // lore lines > MAX_LORE_LINES
        var tooMuchLore = new ArrayDeque<Object>(
                List.of("s", 0, "t", 6, 1, 0, "minecraft:paper", "x", 9));
        assertThrows(IllegalArgumentException.class,
                () -> DeskWire.read(new FakeBuf(tooMuchLore)));
    }

    @Test
    void oversizedWriteFailsBeforeEncoding() {
        var oversized = new ArrayList<DeskWire.Tile>();
        for (int i = 0; i < 60; i++) {
            oversized.add(new DeskWire.Tile(i, "minecraft:paper", "x", List.of()));
        }
        var view = new DeskWire.View("s", 0, "t", 6, oversized);
        assertThrows(IllegalArgumentException.class,
                () -> DeskWire.write(new FakeBuf(), view));
    }

    @Test
    void emptyPageAnswersNoClick() {
        assertNull(new DeskPage("t", 6, List.of()).buttonAt(0),
                "an empty page answers no click");
    }

    @Test
    void sessionIdsStayInsideTheUuidBound() {
        var id = java.util.UUID.randomUUID().toString();
        assertTrue(id.length() <= DeskWire.MAX_SESSION_ID,
                "a UUID session id fits the wire bound");
    }

    @Test
    void oversizedTitleIsTruncatedNotRejected() {
        var view = new DeskWire.View("s", 0, "x".repeat(200), 6, List.of());
        var buf = new FakeBuf();
        DeskWire.write(buf, view);
        assertEquals(DeskWire.MAX_TITLE,
                DeskWire.read(buf.reader()).title().length());
    }

    @Test
    void clickBoundsRejectForgedPackets() {
        assertThrows(IllegalArgumentException.class,
                () -> DeskWire.checkClickBounds(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DeskWire.checkClickBounds(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> DeskWire.checkClickBounds(0, DeskWire.MAX_BUTTONS));
        assertDoesNotThrow(() -> DeskWire.checkClickBounds(0, 0));
    }
}
