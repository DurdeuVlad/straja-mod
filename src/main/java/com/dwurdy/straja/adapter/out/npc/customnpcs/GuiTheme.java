package com.dwurdy.straja.adapter.out.npc.customnpcs;

import java.util.Map;
import java.util.Set;

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
    static final String PANEL_TEXTURE = TEXTURE_ROOT + "panel_bg.png";

    /** Tier-2 generated PNGs via textured rect; Tier-1 item renderers when off. */
    static final boolean USE_TEXTURE_ICONS = true;
    /** Generated parchment panel via {@code setBackgroundTexture} when on. */
    static final boolean USE_PANEL_BACKGROUND = true;

    // Taxonomy keys (docs/icon-generation-prompt-pack.md); admin_selector has no
    // PNG yet and always exercises the item-renderer fallback.
    static final String ICON_SELECTOR = "admin_selector";
    static final String ICON_CLEANUP = "act_cleanup";
    static final String ICON_CONFIRM = "act_assign";
    static final String ICON_UNASSIGN = "act_unassign";
    static final String ICON_STATUS = "act_status";
    static final String ICON_AUDIT = "act_audit";
    static final String ICON_OK = "state_ok";
    static final String ICON_DENIED = "state_denied";
    static final String ICON_INPUT = "act_input";

    private static final Map<String, String> ROLE_ICONS = Map.of(
            "straja.reception.", "role_receptionist",
            "straja.secretary.", "role_secretary",
            "straja.instructor.", "role_instructor",
            "straja.armorer.", "role_armorer",
            "straja.jailer.", "role_jailer",
            "straja.archivist.", "role_archivist");

    /** Keys that ship a generated PNG; anything else uses the item fallback. */
    private static final Set<String> TEXTURED_ICONS = Set.of(
            "role_receptionist", "role_secretary", "role_instructor", "role_armorer",
            "role_jailer", "role_archivist", "act_assign", "act_unassign",
            "act_status", "act_audit", "act_cleanup", "act_input", "quest_active",
            "quest_new", "quest_done", "state_ok", "state_denied", "state_warn");

    private static final Map<String, String> ICON_ITEM_FALLBACKS = Map.ofEntries(
            Map.entry("role_receptionist", "straja:mission_carnet"),
            Map.entry("role_secretary", "straja:archive_stamp"),
            Map.entry("role_instructor", "straja:fine_book"),
            Map.entry("role_armorer", "straja:baton"),
            Map.entry("role_jailer", "straja:cuffs"),
            Map.entry("role_archivist", "straja:archive_folder"),
            Map.entry("admin_selector", "straja:npc_wand"),
            Map.entry("act_assign", "straja:archive_stamp"),
            Map.entry("act_unassign", "straja:bolt_cutters"),
            Map.entry("act_status", "straja:official_envelope"),
            Map.entry("act_audit", "straja:order_book"),
            Map.entry("act_cleanup", "straja:npc_cloner"),
            Map.entry("act_input", "straja:carbon_paper"),
            Map.entry("quest_active", "straja:mission_carnet"),
            Map.entry("quest_new", "straja:official_envelope"),
            Map.entry("quest_done", "straja:archive_stamp"),
            Map.entry("state_ok", "straja:archive_stamp"),
            Map.entry("state_denied", "straja:fine_notice"),
            Map.entry("state_warn", "straja:alarm_whistle"));

    /**
     * Returns the taxonomy icon key for the given profile id, or null when the
     * profile family has no mapped icon. Kept as a pure string mapping so unit
     * tests need no registry bootstrap.
     */
    static String roleIconKey(String profileId) {
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

    /** Tier-1 fallback: Straja item id for a taxonomy icon key, or null. */
    static String iconItemFallback(String iconKey) {
        return iconKey == null ? null : ICON_ITEM_FALLBACKS.get(iconKey);
    }

    /**
     * Returns the Straja item id rendered as the role header icon for the
     * given profile id, or null when the profile family has no mapped icon.
     */
    static String roleIconItemId(String profileId) {
        return iconItemFallback(roleIconKey(profileId));
    }

    /** True when the key ships a generated PNG and may use a textured rect. */
    static boolean hasTextureIcon(String iconKey) {
        return iconKey != null && TEXTURED_ICONS.contains(iconKey);
    }

    /** Resource-location string for a generated icon in the Tier-2 pack. */
    static String iconTexture(String iconId) {
        return TEXTURE_ROOT + "icons/" + iconId + ".png";
    }
}
