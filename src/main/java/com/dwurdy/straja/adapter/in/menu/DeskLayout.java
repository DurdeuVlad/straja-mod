package com.dwurdy.straja.adapter.in.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure page builder: turns ordered content items into a 6-row desk page —
 * content fills row-major from slot 0, the last row is reserved for chrome
 * («Înapoi», page arrows, the guidance/info item). Max 45 content items per
 * page; page-turn and back buttons only appear when applicable.
 */
public final class DeskLayout {
    private DeskLayout() {}

    public static final int ROWS = 6;
    // Slot indices are the wire contract between server page and client screen.
    public static final int COLUMNS = 9;
    public static final int CONTENT_SLOTS = (ROWS - 1) * COLUMNS; // 45
    public static final int BACK_SLOT = 45;
    public static final int PREV_SLOT = 48;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 50;
    // The detail body tile must not collide with content: slot 51 sits in the
    // unused tail of the footer row, outside the 0–44 content grid.
    public static final int DETAIL_SLOT = 51;
    public static final int YES_SLOT = 21;
    public static final int NO_SLOT = 23;
    static final int LORE_WRAP = 35;

    /** Page count for {@code items} content buttons at 45 per page (≥1). */
    public static int pageCount(int items) {
        return Math.max(1, (items + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
    }

    /**
     * Builds a paged grid: slots 0–44 carry content row-major; the footer row
     * carries «Înapoi» (when a nav trail exists), «Pagina x/y» arrows (when a
     * page exists in that direction) and the header info item (always).
     */
    public static DeskPage grid(String title, DeskIcon header, List<DeskButton> items,
                                int page, boolean hasBack) {
        var content = items == null ? List.<DeskButton>of() : items;
        title = title == null ? "" : title;
        int pages = pageCount(content.size());
        int current = Math.min(Math.max(page, 0), pages - 1);
        var placed = new ArrayList<DeskButton>();
        int start = current * CONTENT_SLOTS;
        for (int i = start; i < Math.min(start + CONTENT_SLOTS, content.size()); i++) {
            DeskButton item = content.get(i);
            placed.add(new DeskButton(i - start, item.icon(), item.actionId()));
        }
        if (hasBack) placed.add(backButton("Revii la pagina anterioară."));
        if (current > 0) {
            placed.add(new DeskButton(PREV_SLOT,
                    new DeskIcon("minecraft:spectral_arrow",
                            "« Pagina " + current + "/" + pages,
                            List.of("Pagina anterioară.")),
                    DeskPageSource.PREV));
        }
        placed.add(new DeskButton(INFO_SLOT, withPage(header, current, pages), DeskPageSource.INFO));
        if (current < pages - 1) {
            placed.add(new DeskButton(NEXT_SLOT,
                    new DeskIcon("minecraft:spectral_arrow",
                            "Pagina " + (current + 2) + "/" + pages + " »",
                            List.of("Pagina următoare.")),
                    DeskPageSource.NEXT));
        }
        String pageTitle = pages > 1 ? title + " — " + (current + 1) + "/" + pages : title;
        return new DeskPage(pageTitle, ROWS, placed);
    }

    /**
     * Two-item DA/NU confirm page for a mutating action: the subject item
     * carries the consequence lore; NU and «Înapoi» both return to the page
     * the click came from.
     */
    public static DeskPage confirm(String title, DeskIcon subject, boolean hasBack) {
        var buttons = new ArrayList<DeskButton>();
        buttons.add(new DeskButton(DETAIL_SLOT, subject, DeskPageSource.INFO));
        buttons.add(new DeskButton(YES_SLOT,
                new DeskIcon("minecraft:lime_dye", "DA",
                        List.of("Execută acțiunea.")),
                DeskPageSource.YES));
        buttons.add(new DeskButton(NO_SLOT,
                new DeskIcon("minecraft:red_dye", "NU",
                        List.of("Revii fără să execuți.")),
                DeskPageSource.NO));
        if (hasBack) buttons.add(backButton("Revii la pagina anterioară."));
        return new DeskPage(title, ROWS, buttons);
    }

    /** Single info-item page (FAQ answers): the text body plus «Înapoi». */
    public static DeskPage info(String title, DeskIcon body, boolean hasBack) {
        var buttons = new ArrayList<DeskButton>();
        buttons.add(new DeskButton(DETAIL_SLOT, body, DeskPageSource.INFO));
        if (hasBack) buttons.add(backButton("Revii la întrebări."));
        return new DeskPage(title, ROWS, buttons);
    }

    private static DeskButton backButton(String lore) {
        return new DeskButton(BACK_SLOT,
                new DeskIcon("minecraft:arrow", "« Înapoi", List.of(lore)),
                DeskPageSource.BACK);
    }

    private static DeskIcon withPage(DeskIcon header, int page, int pages) {
        if (header == null || pages <= 1) return header;
        var lore = new ArrayList<>(header.lore());
        lore.add("Pagina " + (page + 1) + " din " + pages + ".");
        return new DeskIcon(header.itemId(), header.name(), lore);
    }

    /**
     * Word-wrap for lore lines at {@code width} characters — never loses
     * words; a word longer than the line breaks hard mid-word.
     */
    public static List<String> wrapLore(String text, int width) {
        if (text == null || text.isBlank() || width < 1) return List.of();
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            while (word.length() > width) {
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return List.copyOf(lines);
    }
}
