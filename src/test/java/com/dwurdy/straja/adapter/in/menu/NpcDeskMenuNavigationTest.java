package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.dwurdy.straja.adapter.in.npc.NpcFaqSurface;
import com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import java.util.List;
import java.util.UUID;
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

    /**
     * Issue-4: section drawers page like the role grid — the Comisar
     * personnel roster can overflow 45 — while «Înapoi» and a cancelled
     * confirm return to the drawer the player came from.
     */
    @Test
    void sectionPagesRepageAndUnwindLikeTheGrid() {
        var nav = new DeskNavigation();
        nav.open(new DeskPageSource.SectionPage(DeskSections.COMISAR, 0));
        assertEquals(1, nav.depth());
        nav.goToPage(3);
        assertEquals(new DeskPageSource.SectionPage(DeskSections.COMISAR, 3),
                nav.current(), "arrows re-page the drawer, keeping its section");
        nav.open(new DeskPageSource.ConfirmPage("admin-promote:uuid-1",
                "Promovează Vlad", List.of("Schimbarea se aplică imediat.")));
        assertEquals(new DeskPageSource.SectionPage(DeskSections.COMISAR, 3),
                nav.back(), "NU returns to the roster page the click came from");
        assertEquals(new DeskPageSource.RolePage(0), nav.back(),
                "«Înapoi» from the drawer lands back on the desk top");
    }

    @Test
    void sectionDrawerSurvivesOnlyInsideItsOwnSection() {
        var nav = new DeskNavigation();
        nav.open(new DeskPageSource.SectionPage(DeskSections.RECORDS, 1));
        nav.open(new DeskPageSource.SectionPage(DeskSections.BOARD, 0));
        assertEquals(new DeskPageSource.SectionPage(DeskSections.BOARD, 0), nav.current());
        assertEquals(new DeskPageSource.SectionPage(DeskSections.RECORDS, 1), nav.back(),
                "the trail keeps each drawer's own page");
    }

    /**
     * Issue-5: admin-tool menus anchor on their own flat list — «Înapoi»
     * and home must never escape into a role grid the session does not own.
     */
    @Test
    void toolListSessionsAnchorOnTheirOwnRoot() {
        var items = List.of(new DeskButton(
                new DeskIcon("straja:npc_wand", "Rol: receptionist", List.of()),
                "tool-npc-assign:uuid-1-receptionist"));
        var root = new DeskPageSource.ListPage("NPC uuid-1", items, 0);
        var nav = new DeskNavigation(root);
        assertEquals(root, nav.current());
        assertEquals(0, nav.depth());
        nav.open(new DeskPageSource.ConfirmPage("tool-npc-remove-confirm:uuid-1",
                "Elimină NPC", List.of("Eliminarea este permanentă.")));
        assertEquals(root, nav.back(),
                "NU on the removal gate returns to the tool list, not a role grid");
        nav.goToPage(1);
        assertEquals(new DeskPageSource.ListPage("NPC uuid-1", items, 1), nav.current(),
                "tool lists page in place like the role grid");
        assertEquals(root, nav.home(),
                "home lands back on the tool list root");
    }

    /**
     * Issue-5: a desk-provider-bound surface anchors on its binding — the
     * trail never fabricates a role page for an NPC the player only knows
     * through the canonical binding.
     */
    @Test
    void boundSurfaceSessionsAnchorOnTheirBinding() {
        var binding = new NpcBinding("straja.bound.desk", NpcProviderId.DESK_MENU,
                UUID.randomUUID().toString(), "", "receptionist", "hq",
                NpcContentId.of("straja.reception.admission"), 1);
        var root = new DeskPageSource.SurfacePage(binding, 0);
        var nav = new DeskNavigation(root);
        assertEquals(root, nav.current());
        nav.goToPage(1);
        assertEquals(new DeskPageSource.SurfacePage(binding, 1), nav.current(),
                "snapshot pages re-page like the role grid");
        nav.open(new DeskPageSource.ConfirmPage("desk:x", "x", List.of()));
        assertEquals(new DeskPageSource.SurfacePage(binding, 1), nav.back(),
                "NU returns to the surface page the click came from");
        assertEquals(root, nav.home(),
                "home lands on the bound surface, not a role grid");
    }
}
