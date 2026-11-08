package com.dwurdy.straja.adapter.in.npc;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
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

    /**
     * Player-bound one-use token mint — public so gateway adapters can attach
     * validated action links to service notices (same contract as desk clicks).
     */
    public static String issueActionToken(UUID playerId, String actionId) {
        return issueActionToken(playerId, actionId, ACTION_TOKEN_TTL_NANOS);
    }

    /** Token mint for the admin-tool menus — same player-bound one-use contract. */
    public static String issueToolActionToken(UUID playerId, String actionId) {
        return issueActionToken(playerId, actionId);
    }

    /**
     * Diagnostic seam: how many live action tokens are bound to a player.
     * Lets tests prove the NPC surface actually dispatched (chat fallback
     * mints one token per action) without replaying chat components.
     */
    public static long pendingActionsFor(UUID playerId) {
        purgeExpiredTokens();
        return ACTION_TOKENS.values().stream()
                .filter(pending -> pending.playerId().equals(playerId)).count();
    }

    /** Issues a token with an explicit TTL — notice links pass the window they gate. */
    public static String issueActionToken(UUID playerId, String actionId, long ttlNanos) {
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
     * one, and the gameplay command roots remain permission-gated.
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

    public static void interact(StrajaNpcEntity npc, Player player, ServerLevel level,
                                net.minecraft.world.InteractionHand hand) {
        // A held book still earns its copy, but the copy is an affordance, not
        // a gate: the desk/chat surface must always open so institution tasks
        // stay reachable through the GUI while holding a book.
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            var gateway = new MinecraftPlayerGateway(
                    serverPlayer.getServer(), serverPlayer.getUUID());
            NpcRoles.trySecretaryBookCopy(npc.getRoleId(), gateway);
            // ITEM-1: the coin gesture — the clicked hand first, then the
            // other hand: the off-hand packet may never arrive, but a
            // prisoner holding coins anywhere is still offering them.
            var clicked = player.getItemInHand(hand);
            var other = player.getItemInHand(
                    hand == net.minecraft.world.InteractionHand.MAIN_HAND
                            ? net.minecraft.world.InteractionHand.OFF_HAND
                            : net.minecraft.world.InteractionHand.MAIN_HAND);
            if (coinBribeGesture(gateway, npc.getStringUUID(),
                    npc.getName().getString(), npc.getRoleId(), itemId(clicked))
                    || coinBribeGesture(gateway, npc.getStringUUID(),
                            npc.getName().getString(), npc.getRoleId(),
                            itemId(other))) {
                return;
            }
        }
        NpcRoles.interact(npc.getRoleId(), player, level);
    }

    /**
     * ITEM-1: coins in hand + an officer is the bribe gesture, shared by the
     * native entity and bound-officer surfaces. The NPC answers by its own
     * honesty trait; a witnessed acceptance relieves the officer through the
     * runtime discipline hook — the adapter never touches application types.
     *
     * @return true when the click was consumed (gesture resolved, including
     *         refused gestures that sent the prisoner a message).
     */
    public static boolean coinBribeGesture(
            com.dwurdy.straja.application.port.out.PlayerGateway gateway,
            String npcUuid, String npcName, String roleId, String heldItemId) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null || !isOfficerRole(roleId)) return false;
        if (heldItemId == null
                || !runtime.policies().coinItemIds.containsValue(heldItemId)) {
            return false;
        }
        // The gesture only exists for a jailed player — a civilian carrying
        // coins still gets the officer's normal desk.
        if (runtime.prisonRoleplay().registerStatus(gateway)
                != com.dwurdy.straja.domain.model.PrisonerStatus.IN_CELL) {
            return false;
        }
        return runtime.escapes().bribeNpc(gateway, npcUuid, npcName,
                com.dwurdy.straja.application.service.EscapeService.corruptible(
                        npcUuid, runtime.policies().npcBribeAcceptPercent))
                != com.dwurdy.straja.application.service.EscapeService
                        .NpcBribeOutcome.IGNORED;
    }

    /** Registry id of a held stack — null-safe. */
    private static String itemId(net.minecraft.world.item.ItemStack stack) {
        return stack == null || stack.isEmpty() ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
    }

    /** Officer roles are the bribeable surface — the force, not the desks. */
    private static boolean isOfficerRole(String roleId) {
        return NpcRoles.PATRULIER.equals(roleId) || NpcRoles.JAILER.equals(roleId);
    }

    /** @return true when the damage is fully handled/denied by the Straja layer. */
    public static boolean onNpcHurt(StrajaNpcEntity npc, DamageSource source, float amount) {
        return NpcRoles.onHurt(npc.getRoleId(), npc, source, amount);
    }

    /** @return true when a jailer-role NPC should take vanilla damage. */
    public static boolean jailerMayTakeDamage(StrajaNpcEntity npc, DamageSource source) {
        return NpcRoles.officerMayTakeDamage(npc, source);
    }
}
