package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.dwurdy.straja.adapter.in.npc.NpcFaqSurface;
import com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Session-independent nav-stack invariants — the reclame.js fix: a cancelled
 * confirm returns to the page it came from, and the FAQ chain unwinds in
 * order. Pure — no Minecraft classes.
 */
class NpcDeskMenuNavigationTest {
    private static NpcFaqSurface.Target target(String kind, String topic, String question) {
        return new NpcFaqSurface.Target(kind, topic, question,
                NpcPlayerSurface.RoleRoute.RECEPTIONIST);
    }

    @Test
    void sessionStartsOnTheRoleGrid() {
        var nav = new DeskNavigation();
        assertEquals(new DeskPageSource.RolePage(0), nav.current());
        assertEquals(0, nav.depth());
    }

    @Test
    void openPushesAndBackPops() {
        var nav = new DeskNavigation();
        nav.goToPage(1);
        nav.open(new DeskPageSource.FaqPage(target("root", "", "")));
        assertEquals(1, nav.depth());
        assertInstanceOf(DeskPageSource.FaqPage.class, nav.current());
        assertEquals(new DeskPageSource.RolePage(1), nav.back());
        assertEquals(0, nav.depth());
    }

    @Test
    void backOnAnEmptyTrailReturnsToTheGrid() {
        var nav = new DeskNavigation();
        assertEquals(new DeskPageSource.RolePage(0), nav.back());
    }

    @Test
    void confirmCancelRestoresTheOriginPage() {
        var nav = new DeskNavigation();
        nav.goToPage(2);
        nav.open(new DeskPageSource.ConfirmPage("fine-pay:F-9",
                "Plătește amenda F-9", List.of("Amenda este achitată.")));
        // NU semantics — the same pop the back button performs.
        assertEquals(new DeskPageSource.RolePage(2), nav.back(),
                "NU must return to the page the click came from");
    }

    @Test
    void confirmYesPopsTheGateBeforeDispatch() {
        var nav = new DeskNavigation();
        nav.open(new DeskPageSource.ConfirmPage("room-release",
                "Eliberează camera", List.of("Camera se eliberează.")));
        assertEquals(new DeskPageSource.RolePage(0), nav.back(),
                "after DA the origin page is current again");
    }

    @Test
    void faqChainUnwindsInOrder() {
        var nav = new DeskNavigation();
        nav.open(new DeskPageSource.FaqPage(target("root", "", "")));
        nav.open(new DeskPageSource.FaqPage(target("menu", "entry", "")));
        nav.open(new DeskPageSource.FaqPage(target("answer", "entry", "join")));
        assertEquals(3, nav.depth());
        assertEquals(new DeskPageSource.FaqPage(target("menu", "entry", "")), nav.back());
        assertEquals(new DeskPageSource.FaqPage(target("root", "", "")), nav.back());
        assertEquals(new DeskPageSource.RolePage(0), nav.back());
    }

    @Test
    void homeDropsTheWholeTrail() {
        var nav = new DeskNavigation();
        nav.open(new DeskPageSource.FaqPage(target("root", "", "")));
        nav.open(new DeskPageSource.FaqPage(target("menu", "entry", "")));
        assertEquals(new DeskPageSource.RolePage(0), nav.home());
        assertEquals(0, nav.depth());
    }

    @Test
    void pagingOnlyAppliesToTheRoleGrid() {
        var nav = new DeskNavigation();
        nav.goToPage(4);
        assertEquals(new DeskPageSource.RolePage(4), nav.current());
        nav.goToPage(-2);
        assertEquals(new DeskPageSource.RolePage(0), nav.current(),
                "negative page clamps to the first page");
        var faq = new DeskPageSource.FaqPage(target("root", "", ""));
        nav.open(faq);
        assertEquals(faq, nav.goToPage(9),
                "FAQ pages are not paged — the source stays put");
    }
}
