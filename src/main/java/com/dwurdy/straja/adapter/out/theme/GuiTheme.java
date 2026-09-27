package com.dwurdy.straja.adapter.out.theme;

import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.Map;
import java.util.Set;

/**
 * Visual tokens for the Straja institutional theme used by CustomNPCs
 * surfaces and the native form screen. Palette values are sampled from the shipped item art; grid and
 * icon rules are defined in {@code docs/npc-surface-visual-system.md}.
 *
 * <p>Icons resolve in two tiers: registered Straja items rendered through the
 * CustomNPCs item-renderer component as the guaranteed base layer, and
 * generated PNGs under {@link #TEXTURE_ROOT} drawn as textured-rect overlays.
 * CustomNPCs 1.21.1.20251230 does not draw textured components client-side;
 * the overlay is a no-op there and upgrades automatically where fixed.</p>
 *
 * <p>Public since M4: {@code StrajaFormScreen} (the native form surface)
 * shares the same palette/grid tokens.</p>
 */
public final class GuiTheme {
    private GuiTheme() {
    }

    public static final int COLOR_PAPER = 0xDCD7BE;
    public static final int COLOR_PAPER_BRIGHT = 0xF0E9C9;
    public static final int COLOR_PAPER_DIM = 0xB4AF96;
    public static final int COLOR_LEATHER = 0x78501E;
    public static final int COLOR_NIGHT = 0x141428;
    public static final int COLOR_SEAL_BRIGHT = 0xA02828;
    public static final int COLOR_BRASS = 0xD2B43C;
    public static final int COLOR_STEEL = 0x787882;

    public static final int MARGIN = 12;
    public static final int CONTENT_WIDTH = 396;
    public static final int HEADER_Y = 8;
    public static final int HEADER_ICON_SIZE = 16;
    public static final int TITLE_X = 34;
    public static final int TITLE_WIDTH = 374;
    public static final int RULE_Y = 30;
    // Standard 421x240 surface grid (docs/npc-surface-visual-system.md §2/§6).
    public static final int GUI_HEIGHT = 240;
    public static final int BODY_Y = 34;
    public static final int BODY_H = 52;
    public static final int COLUMN_TOP = 92;
    public static final int COLUMN_BOTTOM = 206;
    public static final int CHOICE_W = 195;
    public static final int QUEST_X = 218;
    public static final int QUEST_TEXT_X = 238;
    public static final int QUEST_TEXT_W = 170;
    public static final int FOOTER_Y = 214;
    public static final int FOOTER_H = 22;

    public static final String TEXTURE_ROOT = "straja:textures/gui/";
    public static final String PANEL_TEXTURE = TEXTURE_ROOT + "panel_bg.png";

    /** Tier-2 PNG overlay via textured rect (inert on this CNPC build). */
    public static final boolean USE_TEXTURE_ICONS = true;
    /** Generated parchment panel via {@code setBackgroundTexture} when on. */
    public static final boolean USE_PANEL_BACKGROUND = true;

    // Taxonomy keys (docs/icon-generation-prompt-pack.md); admin_selector has no
    // PNG yet and always exercises the item-renderer fallback.
    public static final String ICON_SELECTOR = "admin_selector";
    public static final String ICON_CLEANUP = "act_cleanup";
    public static final String ICON_CONFIRM = "act_assign";
    public static final String ICON_UNASSIGN = "act_unassign";
    public static final String ICON_STATUS = "act_status";
    public static final String ICON_AUDIT = "act_audit";
    public static final String ICON_OK = "state_ok";
    public static final String ICON_DENIED = "state_denied";
    public static final String ICON_WARN = "state_warn";
    public static final String ICON_INPUT = "act_input";
    public static final String ICON_QUEST_ACTIVE = "quest_active";
    public static final String ICON_QUEST_NEW = "quest_new";
    public static final String ICON_QUEST_DONE = "quest_done";

    // Content-profile ids are dotted ("straja.jailer.custody") while the
    // provisioning selector exposes npcProfileId ("straja:jailer") — match both.
    private static final Map<String, String> ROLE_ICONS = Map.of(
            "straja.reception.", "role_receptionist",
            "straja.secretary.", "role_secretary",
            "straja.instructor.", "role_instructor",
            "straja.armorer.", "role_armorer",
            "straja.jailer.", "role_jailer",
            "straja.archivist.", "role_archivist");
    private static final Map<String, String> NPC_ROLE_ICONS = Map.of(
            "straja:receptionist", "role_receptionist",
            "straja:secretary", "role_secretary",
            "straja:trainer", "role_instructor",
            "straja:armorer", "role_armorer",
            "straja:jailer", "role_jailer",
            "straja:archivist", "role_archivist");

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
    public static String roleIconKey(String profileId) {
        if (profileId == null) {
            return null;
        }
        String npcForm = NPC_ROLE_ICONS.get(profileId);
        if (npcForm != null) {
            return npcForm;
        }
        for (Map.Entry<String, String> entry : ROLE_ICONS.entrySet()) {
            if (profileId.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Tier-1 fallback: Straja item id for a taxonomy icon key, or null. */
    public static String iconItemFallback(String iconKey) {
        return iconKey == null ? null : ICON_ITEM_FALLBACKS.get(iconKey);
    }

    /**
     * Returns the Straja item id rendered as the role header icon for the
     * given profile id, or null when the profile family has no mapped icon.
     */
    public static String roleIconItemId(String profileId) {
        return iconItemFallback(roleIconKey(profileId));
    }

    /** True when the key ships a generated PNG and may use a textured rect. */
    public static boolean hasTextureIcon(String iconKey) {
        return iconKey != null && TEXTURED_ICONS.contains(iconKey);
    }

    /** Quest-journal state glyph: taxonomy icon key for a quest state. */
    public static String questIconKey(NpcSurfaceSnapshot.QuestState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case AVAILABLE -> ICON_QUEST_NEW;
            case ACTIVE -> ICON_QUEST_ACTIVE;
            case COMPLETED -> ICON_QUEST_DONE;
            case FAILED -> ICON_DENIED;
            case LOCKED -> ICON_WARN;
        };
    }

    /** Quest-journal line color by state; dim for finished, steel for locked. */
    public static int questLabelColor(NpcSurfaceSnapshot.QuestState state) {
        if (state == null) {
            return COLOR_PAPER;
        }
        return switch (state) {
            case ACTIVE -> COLOR_PAPER_BRIGHT;
            case AVAILABLE -> COLOR_PAPER;
            case COMPLETED -> COLOR_PAPER_DIM;
            case FAILED -> COLOR_SEAL_BRIGHT;
            case LOCKED -> COLOR_STEEL;
        };
    }

    /** Resource-location string for a generated icon in the Tier-2 pack. */
    public static String iconTexture(String iconId) {
        return TEXTURE_ROOT + "icons/" + iconId + ".png";
    }
}
