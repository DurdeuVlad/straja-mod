package com.dwurdy.straja.adapter.in.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * The desk GUI wire contract — Minecraft-free so the headless test classpath
 * can pin it. A {@link View} is what crosses the network: the rendered page's
 * title, row count, and one {@link Tile} per placed button (slot, icon item
 * id, name, lore). Action ids never enter the wire view — the server resolves
 * a returning click's slot against the page it rendered.
 *
 * <p>{@link Buf} is the four-method buffer surface the codec needs;
 * {@code DeskGuiMenu} adapts {@code FriendlyByteBuf} to it. Bounds are
 * enforced on both write and read so a malformed packet fails closed.</p>
 */
public final class DeskWire {
    private DeskWire() {}

    static final int MAX_SESSION_ID = 40;
    static final int MAX_TITLE = 80;
    static final int MAX_ROWS = 6;
    static final int MAX_BUTTONS = 54;
    static final int MAX_ITEM_ID = 80;
    static final int MAX_NAME = 80;
    static final int MAX_LORE_LINES = 6;
    static final int MAX_LORE_LINE = 160;

    /** The minimal buffer surface the codec requires. */
    public interface Buf {
        void writeUtf(String value, int maxLength);
        void writeVarInt(int value);
        String readUtf(int maxLength);
        int readVarInt();
    }

    /** What the client renders: session id, page sequence, and the cells. */
    public record View(String sessionId, int seq, String title, int rows,
                       List<Tile> tiles) {
        public View {
            sessionId = sessionId == null ? "" : sessionId;
            title = title == null ? "" : title;
            tiles = tiles == null ? List.of() : List.copyOf(tiles);
        }
    }

    /** One rendered cell of the desk grid. */
    public record Tile(int slot, String itemId, String name, List<String> lore) {
        public Tile {
            itemId = itemId == null || itemId.isBlank() ? DeskIcon.FALLBACK_ITEM : itemId;
            name = name == null ? "" : name;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    /** Projects a server-side {@link DeskPage} into the client wire view. */
    public static View viewOf(String sessionId, int seq, DeskPage page) {
        var tiles = new ArrayList<Tile>(page.buttons().size());
        for (DeskButton button : page.buttons()) {
            DeskIcon icon = button.icon();
            tiles.add(new Tile(button.slot(),
                    icon == null ? DeskIcon.FALLBACK_ITEM : icon.itemId(),
                    icon == null ? "" : icon.name(),
                    icon == null ? List.of() : icon.lore()));
        }
        return new View(sessionId, seq, page.title(), page.rows(), tiles);
    }

    public static void write(Buf buf, View view) {
        if (view.tiles().size() > MAX_BUTTONS) {
            throw new IllegalArgumentException(
                    "desk button count out of bounds: " + view.tiles().size());
        }
        buf.writeUtf(view.sessionId(), MAX_SESSION_ID);
        buf.writeVarInt(view.seq());
        buf.writeUtf(truncate(view.title(), MAX_TITLE), MAX_TITLE);
        buf.writeVarInt(view.rows());
        buf.writeVarInt(view.tiles().size());
        for (Tile tile : view.tiles()) {
            buf.writeVarInt(tile.slot());
            buf.writeUtf(tile.itemId(), MAX_ITEM_ID);
            buf.writeUtf(tile.name(), MAX_NAME);
            buf.writeVarInt(tile.lore().size());
            for (String line : tile.lore()) {
                buf.writeUtf(line, MAX_LORE_LINE);
            }
        }
    }

    public static View read(Buf buf) {
        String sessionId = buf.readUtf(MAX_SESSION_ID);
        int seq = buf.readVarInt();
        String title = buf.readUtf(MAX_TITLE);
        int rows = buf.readVarInt();
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("desk rows out of bounds: " + rows);
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_BUTTONS) {
            throw new IllegalArgumentException(
                    "desk button count out of bounds: " + count);
        }
        var tiles = new ArrayList<Tile>(count);
        for (int i = 0; i < count; i++) {
            int slot = buf.readVarInt();
            String itemId = buf.readUtf(MAX_ITEM_ID);
            String name = buf.readUtf(MAX_NAME);
            int loreLines = buf.readVarInt();
            if (loreLines < 0 || loreLines > MAX_LORE_LINES) {
                throw new IllegalArgumentException(
                        "desk lore lines out of bounds: " + loreLines);
            }
            var lore = new ArrayList<String>(loreLines);
            for (int l = 0; l < loreLines; l++) {
                lore.add(buf.readUtf(MAX_LORE_LINE));
            }
            tiles.add(new Tile(slot, itemId, name, lore));
        }
        return new View(sessionId, seq, title, rows, tiles);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * C→S click bounds — a forged packet must fail closed before it ever
     * reaches session resolution: no negative sequence, no slot outside the
     * tile grid.
     */
    static void checkClickBounds(int seq, int slot) {
        if (seq < 0 || slot < 0 || slot >= MAX_BUTTONS) {
            throw new IllegalArgumentException(
                    "desk_click out of bounds: seq=" + seq + " slot=" + slot);
        }
    }
}
