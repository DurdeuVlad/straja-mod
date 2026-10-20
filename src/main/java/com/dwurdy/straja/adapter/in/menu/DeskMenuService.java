package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.config.StrajaServerConfig;
import com.dwurdy.straja.domain.model.Text;
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
    private DeskMenuService() {}

    /**
     * Opens the desk chest-menu for the Receptionist pilot. The flag is
     * TOML-only for v1 — {@code StrajaPolicies} stays untouched (domain), so
     * the straja-policies.yaml override layer does not cover it.
     */
    public static boolean tryOpen(Player player, String roleId,
                                  NpcPlayerSurface.OrderedSurface ordered) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || ordered == null) {
                return false;
            }
            if (!enabled() || StrajaRuntime.get() == null) return false;
            // Issue-1 pilot scope: only the Receptionist desk; other roles
            // keep the chat surface until their desk issues land.
            if (NpcPlayerSurface.routeFor(roleId)
                    != NpcPlayerSurface.RoleRoute.RECEPTIONIST) {
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
