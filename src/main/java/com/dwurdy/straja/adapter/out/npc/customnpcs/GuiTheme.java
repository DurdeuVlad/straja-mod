package com.dwurdy.straja.adapter.out.npc.customnpcs;

import java.util.Map;

/**
 * Visual tokens for the Straja institutional theme used by CustomNPCs
 * surfaces. Palette values are sampled from the shipped item art; grid and
 * icon rules are defined in {@code docs/npc-surface-visual-system.md}.
 *
 * <p>Icons resolve in two tiers: registered Straja items rendered through the
 * CustomNPCs item-renderer component (zero new assets), and generated texture
 * files under {@link #TEXTURE_ROOT} wired through textured rects/buttons once
 * the icon pack lands.</p>
 */
final class GuiTheme {
    private GuiTheme() {
    }

    static final int COLOR_PAPER = 0xDCD7BE;
    static final int COLOR_PAPER_BRIGHT = 0xF0E9C9;
    static final int COLOR_PAPER_DIM = 0xB4AF96;
    static final int COLOR_LEATHER = 0x78501E;
    static final int COLOR_SEAL_BRIGHT = 0xA02828;
    static final int COLOR_BRASS = 0xD2B43C;

    static final int MARGIN = 12;
    static final int CONTENT_WIDTH = 396;
    static final int HEADER_Y = 8;
    static final int HEADER_ICON_SIZE = 16;
    static final int TITLE_X = 34;
    static final int TITLE_WIDTH = 374;
    static final int RULE_Y = 30;

    static final String TEXTURE_ROOT = "straja:textures/gui/";
    static final String PANEL_TEXTURE = TEXTURE_ROOT + "surface_panel.png";

    static final String ICON_ADMIN_WAND = "straja:npc_wand";
    static final String ICON_CLEANUP = "straja:npc_cloner";
    static final String ICON_CONFIRM = "straja:archive_stamp";
    static final String ICON_UNASSIGN = "straja:bolt_cutters";
    static final String ICON_STATUS = "straja:official_envelope";
    static final String ICON_AUDIT = "straja:order_book";
    static final String ICON_DENIED = "straja:fine_notice";
    static final String ICON_INPUT = "straja:carbon_paper";

    private static final Map<String, String> ROLE_ICONS = Map.of(
            "straja.reception.", "straja:mission_carnet",
            "straja.secretary.", "straja:archive_stamp",
            "straja.instructor.", "straja:fine_book",
            "straja.armorer.", "straja:baton",
            "straja.jailer.", "straja:cuffs",
            "straja.archivist.", "straja:archive_folder");

    /**
     * Returns the Straja item id rendered as the role header icon for the
     * given profile id, or null when the profile family has no mapped icon.
     * Kept as a pure string mapping so unit tests need no registry bootstrap.
     */
    static String roleIconItemId(String profileId) {
        if (profileId == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : ROLE_ICONS.entrySet()) {
            if (profileId.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Resource-location string for a generated icon in the Tier-2 pack. */
    static String iconTexture(String iconId) {
        return TEXTURE_ROOT + "icons/" + iconId + ".png";
    }
}
