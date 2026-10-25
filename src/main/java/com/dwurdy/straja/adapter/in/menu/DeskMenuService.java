package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface;
import com.dwurdy.straja.application.port.in.AdminToolsUseCase;
import com.dwurdy.straja.application.port.out.DeskSurfaceBridge;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.config.StrajaServerConfig;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Entry point for the desk-menu surface. {@code tryOpen} returns false — so
 * the caller falls back to the chat surface — whenever the player is not a
 * {@link ServerPlayer} (virtual/headless), {@code npcMenus.enabled} is off,
 * or anything fails (logged, never thrown). Holds no persistent state: a
 * session lives and dies with its container.
 */
public final class DeskMenuService {
    /**
     * Routes with a shipped desk — every player-facing role (including the
     * Commander's army desk) opens the native {@code straja:desk} menu; the
     * set stays the explicit rollout switch in case a desk ever needs
     * pulling back to chat. RECRUITER is listed for completeness:
     * {@code routeFor} already folds the recruiter alias into
     * {@code TRAINER}.
     */
    static final Set<NpcPlayerSurface.RoleRoute> ENABLED_ROUTES = Set.of(
            NpcPlayerSurface.RoleRoute.RECEPTIONIST,
            NpcPlayerSurface.RoleRoute.SECRETARY,
            NpcPlayerSurface.RoleRoute.TRAINER,
            NpcPlayerSurface.RoleRoute.RECRUITER,
            NpcPlayerSurface.RoleRoute.JAILER,
            NpcPlayerSurface.RoleRoute.INSPECTOR,
            NpcPlayerSurface.RoleRoute.ARCHIVIST,
            NpcPlayerSurface.RoleRoute.ARMORER,
            NpcPlayerSurface.RoleRoute.COMMANDER);

    private DeskMenuService() {}

    /**
     * Opens the desk chest-menu for an enabled role. The flag is TOML-only
     * for v1 — {@code StrajaPolicies} stays untouched (domain), so the
     * straja-policies.yaml override layer does not cover it.
     */
    public static boolean tryOpen(Player player, String roleId,
                                  NpcPlayerSurface.OrderedSurface ordered) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || ordered == null) {
                return false;
            }
            if (!enabled() || StrajaRuntime.get() == null) return false;
            // The rollout set covers every shipped desk; a route outside it
            // falls back to the chat surface untouched.
            if (!ENABLED_ROUTES.contains(NpcPlayerSurface.routeFor(roleId))) {
                return false;
            }
            return new DeskMenuSession(serverPlayer, roleId).open(ordered);
        } catch (Throwable openFailed) {
            StrajaMod.LOGGER.warn(Text.STRAJA_PREFIX
                    + "Desk menu failed to open; chat surface used instead", openFailed);
            return false;
        }
    }

    /**
     * One (label, actionId) row of a flat desk list — the shape admin-tool
     * menus and single prompts arrive in.
     */
    public record ListItem(String label, String actionId) {}

    /**
     * Renders an arbitrary flat action list as a desk page: every row
     * dispatches {@code NpcRoles.performAction} directly — no chat tokens
     * are minted — and a {@code tool-npc-remove:<uuid>} row is rewritten to
     * its confirm op so the destructive click passes through the in-GUI
     * DA/NU page (the chat fallback keeps its own two-step confirm).
     */
    public static boolean openList(Player player, String title, List<ListItem> items) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)
                    || items == null || items.isEmpty()) {
                return false;
            }
            if (!enabled() || StrajaRuntime.get() == null) return false;
            var buttons = new ArrayList<DeskButton>();
            for (var item : items) {
                if (item == null || item.actionId() == null || item.actionId().isBlank()) {
                    continue;
                }
                String actionId = toolActionFor(item.actionId());
                buttons.add(new DeskButton(
                        DeskActionStyle.icon(item.label(), actionId), actionId));
            }
            if (buttons.isEmpty()) return false;
            return DeskMenuSession.forList(serverPlayer, cleanTitle(title), buttons).open();
        } catch (Throwable openFailed) {
            StrajaMod.LOGGER.warn(Text.STRAJA_PREFIX
                    + "Desk list failed to open; chat surface used instead", openFailed);
            return false;
        }
    }

    /**
     * Admin-tool menus (npc wand role picker, survey stamp targets) render
     * as a desk list; the chat fallback stays byte-identical to today.
     */
    public static boolean openToolMenu(Player player, AdminToolsUseCase.Menu menu) {
        if (menu == null) return false;
        var items = new ArrayList<ListItem>();
        for (var action : menu.actions()) {
            if (action != null) items.add(new ListItem(action.label(), action.actionId()));
        }
        return openList(player, menu.title(), items);
    }

    /**
     * A standalone live-data page — a chat-token click (digest row, action
     * link) opens the matching desk page as a fresh session rooted there.
     * Returns false for virtual players, a disabled flag, or unknown kinds,
     * so the caller keeps its chat fallback.
     */
    public static boolean openDynamic(Player player, String kind, String recordId) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)
                    || kind == null || kind.isBlank()) {
                return false;
            }
            if (!enabled() || StrajaRuntime.get() == null) return false;
            return DeskMenuSession.forDynamic(serverPlayer, kind,
                    recordId == null ? "" : recordId).open();
        } catch (Throwable openFailed) {
            StrajaMod.LOGGER.warn(Text.STRAJA_PREFIX
                    + "Dynamic desk page failed to open", openFailed);
            return false;
        }
    }

    /**
     * Opens the published surface of a desk-provider-bound NPC as a chest
     * grid — the session resolves the snapshot through {@code bridge} on
     * every render and submits clicks through the provider token path.
     */
    public static boolean openBoundSurface(Player player, NpcBinding binding,
                                           DeskSurfaceBridge bridge) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)
                    || binding == null || bridge == null) {
                return false;
            }
            if (!enabled() || StrajaRuntime.get() == null) return false;
            return DeskMenuSession.forSurface(serverPlayer, binding, bridge).open();
        } catch (Throwable openFailed) {
            StrajaMod.LOGGER.warn(Text.STRAJA_PREFIX
                    + "Bound desk surface failed to open", openFailed);
            return false;
        }
    }

    /**
     * The chat surface turns {@code tool-npc-remove:<uuid>} into a second
     * clickable confirm line; the desk renders that gate in-page, so the
     * grid row targets the confirm op directly and DA/NU does the rest.
     */
    private static String toolActionFor(String actionId) {
        return actionId.startsWith("tool-npc-remove:")
                && !actionId.startsWith("tool-npc-remove-confirm:")
                ? "tool-npc-remove-confirm:" + actionId.substring("tool-npc-remove:".length())
                : actionId;
    }

    /** Chest titles drop the chat prefix and any trailing colon. */
    private static String cleanTitle(String title) {
        String cleaned = title == null ? "" : title.strip();
        if (cleaned.startsWith(Text.STRAJA_PREFIX)) {
            cleaned = cleaned.substring(Text.STRAJA_PREFIX.length()).strip();
        }
        if (cleaned.endsWith(":")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
        }
        return cleaned.isBlank() ? "Straja" : cleaned;
    }

    private static boolean enabled() {
        try {
            return StrajaServerConfig.NPC_MENUS_ENABLED.get();
        } catch (RuntimeException configNotLoaded) {
            return false;
        }
    }
}
