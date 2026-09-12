package com.dwurdy.straja.adapter.in.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/**
 * Routes NPC interactions and damage to the role-specific domain service.
 * Every action available here is also available through /straja commands so
 * the server console can drive the same flows without a client.
 */
public final class NpcInteractionService {
    private NpcInteractionService() {}

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
