package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.config.StrajaServerConfig;
import com.dwurdy.straja.domain.model.Text;
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
     * Routes with a shipped desk (Issues 1–4) — every player-facing role now
     * opens the chest menu; the set stays the explicit rollout switch in case
     * a desk ever needs pulling back to chat. RECRUITER is listed for
     * completeness: {@code routeFor} already folds the recruiter alias into
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
            NpcPlayerSurface.RoleRoute.ARMORER);

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

    private static boolean enabled() {
        try {
            return StrajaServerConfig.NPC_MENUS_ENABLED.get();
        } catch (RuntimeException configNotLoaded) {
            return false;
        }
    }
}
