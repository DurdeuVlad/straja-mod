package com.dwurdy.straja.adapter.in.npc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/**
 * Routes NPC interactions and damage to the role-specific domain service.
 * NPC actions are exposed to players as short-lived clickable chat actions;
 * typed gameplay roots remain an administrator/reference surface.
 */
public final class NpcInteractionService {
    private static final long ACTION_TOKEN_TTL_NANOS = 15_000_000_000L;
    private static final ConcurrentMap<String, PendingAction> ACTION_TOKENS = new ConcurrentHashMap<>();

    private NpcInteractionService() {}

    static NpcPlayerSurface.RoleRoute routeFor(String roleId) {
        return NpcPlayerSurface.routeFor(roleId);
    }

    static String issueActionCommand(Player player, NpcPlayerSurface.ChatAction action) {
        return action.command(issueActionToken(player.getUUID(), action.actionId()));
    }

    static String issueActionToken(UUID playerId, String actionId) {
        return issueActionToken(playerId, actionId, ACTION_TOKEN_TTL_NANOS);
    }

    /** Test seam: issues a token with an explicit TTL. */
    static String issueActionToken(UUID playerId, String actionId, long ttlNanos) {
        purgeExpiredTokens();
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (ACTION_TOKENS.putIfAbsent(token,
                new PendingAction(playerId, actionId,
                        System.nanoTime() + Math.max(0, ttlNanos))) != null);
        return token;
    }

    /**
     * Consumes a player-bound, one-use NPC token. A typed command cannot create
     * one, and the gameplay command roots remain permission-2 gated.
     */
    public static String consumeActionToken(String token, UUID playerId) {
        if (token == null || playerId == null) return null;
        PendingAction pending = ACTION_TOKENS.get(token);
        long now = System.nanoTime();
        if (pending == null || pending.expiresAtNanos() - now <= 0
                || !pending.playerId().equals(playerId)) {
            if (pending != null && pending.expiresAtNanos() - now <= 0) {
                ACTION_TOKENS.remove(token, pending);
            }
            return null;
        }
        if (!ACTION_TOKENS.remove(token, pending)) return null;
        return pending.actionId();
    }

    /** Drops expired action tokens; invoked on issue and on the server tick. */
    public static void purgeExpiredTokens() {
        long now = System.nanoTime();
        ACTION_TOKENS.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() - now <= 0);
    }

    private record PendingAction(UUID playerId, String actionId, long expiresAtNanos) {}

    public static void interact(StrajaNpcEntity npc, Player player, ServerLevel level) {
        NpcRoles.interact(npc.getRoleId(), npc, player, level);
    }

    /** @return true when the damage is fully handled/denied by the Straja layer. */
    public static boolean onNpcHurt(StrajaNpcEntity npc, DamageSource source, float amount) {
        return NpcRoles.onHurt(npc.getRoleId(), npc, source, amount);
    }

    /** @return true when a jailer-role NPC should take vanilla damage. */
    public static boolean jailerMayTakeDamage(StrajaNpcEntity npc, DamageSource source) {
        return NpcRoles.jailerMayTakeDamage(npc, source);
    }
}
