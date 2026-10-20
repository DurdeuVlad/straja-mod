package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.adapter.in.npc.NpcFaqSurface;
import com.dwurdy.straja.domain.model.NpcBinding;
import java.util.List;

/**
 * A re-renderable page target — the nav stack stores these so a session can
 * rebuild a page without knowing Minecraft types. Internal nav action ids use
 * the {@code desk:} namespace and never reach {@code NpcRoles.performAction}.
 */
public sealed interface DeskPageSource {
    String BACK = "desk:back";
    String PREV = "desk:prev";
    String NEXT = "desk:next";
    String INFO = "desk:info";
    String YES = "desk:yes";
    String NO = "desk:no";
    /**
     * Nav prefix for provider-bound surface rows: the raw snapshot action id
     * follows it, and a click dispatches through the desk's provider token
     * bridge ({@code DeskSurfaceBridge}) instead of {@code performAction}.
     */
    String SURFACE_PREFIX = "desk:surface:";

    /** One page of the NPC's role surface grid (paged at 45 items). */
    record RolePage(int page) implements DeskPageSource {}

    /**
     * One section drawer of the Secretary desk (Issue 4): renders only the
     * actions {@link DeskSections} classifies under {@code section}, paged at
     * 45 like the role grid. Reached via {@code desk:section:<key>} nav ids.
     */
    record SectionPage(String section, int page) implements DeskPageSource {}

    /** A FAQ tree node: target kind is "root" | "menu" | "answer". */
    record FaqPage(NpcFaqSurface.Target target) implements DeskPageSource {}

    /**
     * In-GUI DA/NU gate for a mutating action id; the session re-renders the
     * origin page on NU (the reclame.js context fix) and on DA after dispatch.
     */
    record ConfirmPage(String actionId, String label, List<String> detail) implements DeskPageSource {
        public ConfirmPage {
            detail = detail == null ? List.of() : List.copyOf(detail);
        }
    }

    /**
     * A flat non-role action grid — admin-tool menus (npc wand, survey rod,
     * cell confirm) rendered as a desk. Rows carry their own action ids and
     * dispatch through {@code performAction} exactly like a role grid.
     */
    record ListPage(String title, List<DeskButton> items, int page) implements DeskPageSource {
        public ListPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * One page of a provider-bound NPC surface rendered by the desk-menu
     * provider: the session re-resolves the published snapshot on every
     * render and wraps each enabled action in a {@link #SURFACE_PREFIX} nav
     * id, so clicks mint/submit provider action tokens — never chat tokens.
     */
    record SurfacePage(NpcBinding binding, int page) implements DeskPageSource {}
}
