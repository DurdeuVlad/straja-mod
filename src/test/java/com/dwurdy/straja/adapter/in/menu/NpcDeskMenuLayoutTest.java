package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure view-model tests for {@link DeskLayout} paging math, footer chrome
 * rules and slot assignment — no Minecraft classes needed.
 */
class NpcDeskMenuLayoutTest {
    private static DeskIcon header() {
        return new DeskIcon("straja:mission_carnet", "Recepție",
                List.of("Depui aici cererea."));
    }

    private static List<DeskButton> items(int count) {
        var items = new ArrayList<DeskButton>();
        for (int i = 0; i < count; i++) {
            items.add(new DeskButton(
                    new DeskIcon("minecraft:paper", "Acțiunea " + i, List.of()),
                    "action-" + i));
        }
        return items;
    }

    private static List<Integer> slots(DeskPage page) {
        return page.buttons().stream().map(DeskButton::slot).toList();
    }

    @Test
    void pageCountCoversTheBoundaries() {
        assertEquals(1, DeskLayout.pageCount(0));
        assertEquals(1, DeskLayout.pageCount(1));
        assertEquals(1, DeskLayout.pageCount(44));
        assertEquals(1, DeskLayout.pageCount(45));
        assertEquals(2, DeskLayout.pageCount(46));
        assertEquals(2, DeskLayout.pageCount(89));
        assertEquals(2, DeskLayout.pageCount(90));
        assertEquals(3, DeskLayout.pageCount(91));
        assertEquals(3, DeskLayout.pageCount(135));
    }

    @Test
    void emptyGridRendersOnlyTheInfoItem() {
        var page = DeskLayout.grid("Recepție", header(), items(0), 0, false);
        assertEquals(DeskLayout.ROWS, page.rows());
        assertEquals(List.of(DeskLayout.INFO_SLOT), slots(page));
        assertEquals(DeskPageSource.INFO, page.buttonAt(DeskLayout.INFO_SLOT).actionId());
    }

    @Test
    void contentFillsRowMajorFromSlotZero() {
        var page = DeskLayout.grid("Recepție", header(), items(12), 0, false);
        for (int i = 0; i < 12; i++) {
            var button = page.buttonAt(i);
            assertNotNull(button, "slot " + i + " must hold a content button");
            assertEquals("action-" + i, button.actionId());
        }
        assertNull(page.buttonAt(12), "first free content slot stays empty");
    }

    @Test
    void fortyFiveItemsFillOnePageWithoutArrows() {
        var page = DeskLayout.grid("Recepție", header(), items(45), 0, false);
        assertEquals("Recepție", page.title(), "single page keeps the bare title");
        for (int i = 0; i < 45; i++) assertNotNull(page.buttonAt(i));
        assertNull(page.buttonAt(DeskLayout.PREV_SLOT));
        assertNull(page.buttonAt(DeskLayout.NEXT_SLOT));
        assertNotNull(page.buttonAt(DeskLayout.INFO_SLOT));
    }

    @Test
    void fortySixItemsSplitOntoTwoPages() {
        var all = items(46);
        var first = DeskLayout.grid("Recepție", header(), all, 0, false);
        var second = DeskLayout.grid("Recepție", header(), all, 1, false);

        assertEquals("Recepție — 1/2", first.title());
        assertEquals("Recepție — 2/2", second.title());
        assertNull(first.buttonAt(DeskLayout.PREV_SLOT));
        assertNotNull(first.buttonAt(DeskLayout.NEXT_SLOT));
        assertNotNull(second.buttonAt(DeskLayout.PREV_SLOT));
        assertNull(second.buttonAt(DeskLayout.NEXT_SLOT));
        assertEquals("action-45", second.buttonAt(0).actionId());
        assertTrue(slots(first).stream().allMatch(s -> s < DeskLayout.CONTENT_SLOTS
                || s >= DeskLayout.BACK_SLOT), "content never leaks into the footer row");
    }

    @Test
    void ninetyItemsFitTwoFullPages() {
        var all = items(90);
        var second = DeskLayout.grid("Recepție", header(), all, 1, false);
        for (int i = 0; i < 45; i++) {
            assertNotNull(second.buttonAt(i), "second page slot " + i);
            assertEquals("action-" + (45 + i), second.buttonAt(i).actionId());
        }
        assertNotNull(second.buttonAt(DeskLayout.PREV_SLOT));
        assertNull(second.buttonAt(DeskLayout.NEXT_SLOT));
    }

    @Test
    void pageIndexClampsIntoRange() {
        var all = items(46);
        var under = DeskLayout.grid("Recepție", header(), all, -3, false);
        var over = DeskLayout.grid("Recepție", header(), all, 99, false);
        assertEquals("Recepție — 1/2", under.title());
        assertEquals("Recepție — 2/2", over.title());
        assertEquals("action-45", over.buttonAt(0).actionId());
    }

    @Test
    void footerShowsBackOnlyWithANavTrail() {
        var all = items(3);
        assertNull(DeskLayout.grid("T", header(), all, 0, false)
                .buttonAt(DeskLayout.BACK_SLOT));
        var withTrail = DeskLayout.grid("T", header(), all, 0, true)
                .buttonAt(DeskLayout.BACK_SLOT);
        assertNotNull(withTrail);
        assertEquals(DeskPageSource.BACK, withTrail.actionId());
        assertEquals("« Înapoi", withTrail.icon().name());
    }

    @Test
    void pagedHeaderCarriesThePageCounter() {
        var all = items(46);
        var info = DeskLayout.grid("Recepție", header(), all, 1, false)
                .buttonAt(DeskLayout.INFO_SLOT).icon();
        assertTrue(info.lore().contains("Pagina 2 din 2."));
        var single = DeskLayout.grid("Recepție", header(), items(2), 0, false)
                .buttonAt(DeskLayout.INFO_SLOT).icon();
        assertTrue(single.lore().stream().noneMatch(line -> line.startsWith("Pagina")));
    }

    @Test
    void confirmPageIsATwoItemGateWithSubjectDetail() {
        var subject = new DeskIcon("minecraft:gold_ingot", "Plătește amenda F-1",
                List.of("Amenda este achitată imediat."));
        var page = DeskLayout.confirm("Confirmă", subject, true);
        assertEquals(subject, page.buttonAt(DeskLayout.DETAIL_SLOT).icon());
        assertEquals(DeskPageSource.YES, page.buttonAt(DeskLayout.YES_SLOT).actionId());
        assertEquals(DeskPageSource.NO, page.buttonAt(DeskLayout.NO_SLOT).actionId());
        assertEquals("DA", page.buttonAt(DeskLayout.YES_SLOT).icon().name());
        assertEquals("NU", page.buttonAt(DeskLayout.NO_SLOT).icon().name());
        assertNotNull(page.buttonAt(DeskLayout.BACK_SLOT));
    }

    @Test
    void infoPageCarriesTheBodyItemAndBack() {
        var body = new DeskIcon("minecraft:writable_book", "Ce este Straja?",
                List.of("Straja protejează Castelul."));
        var page = DeskLayout.info("FAQ", body, true);
        assertEquals(body, page.buttonAt(DeskLayout.DETAIL_SLOT).icon());
        assertEquals(DeskPageSource.BACK, page.buttonAt(DeskLayout.BACK_SLOT).actionId());
        assertNull(page.buttonAt(0), "info pages keep the grid empty");
    }

    @Test
    void wrapLoreBoundsWidthAndKeepsEveryWord() {
        String text = "Depui aici cererea de admitere, alegi regulamentul Străjii "
                + "și verifici situația.";
        var lines = DeskLayout.wrapLore(text, 35);
        assertTrue(lines.size() >= 3, "guidance wraps to several lines: " + lines);
        assertTrue(lines.stream().allMatch(line -> line.length() <= 35));
        assertTrue(String.join(" ", lines).replaceAll("\\s+", " ")
                .contains("cererea de admitere"));

        assertEquals(List.of(), DeskLayout.wrapLore("", 35));
        assertEquals(List.of(), DeskLayout.wrapLore(null, 35));
        var broken = DeskLayout.wrapLore("abcdefghijklmnop", 5);
        assertTrue(broken.stream().allMatch(line -> line.length() <= 5));
        assertEquals("abcdefghijklmnop", String.join("", broken));
    }

    @Test
    void deskIconFallsBackAndNeverThrows() {
        var icon = new DeskIcon(null, null, null);
        assertEquals("minecraft:paper", icon.itemId());
        assertEquals("", icon.name());
        assertEquals(List.of(), icon.lore());
        assertEquals("minecraft:paper", new DeskIcon(" ", "x", null).itemId());
    }
}
