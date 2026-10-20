package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.adapter.in.npc.NpcFaqSurface;
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

    /** One page of the NPC's role surface grid (paged at 45 items). */
    record RolePage(int page) implements DeskPageSource {}

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
}
