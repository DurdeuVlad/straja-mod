package com.dwurdy.straja.adapter.in.npc;

import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/**
 * Registry of Straja NPC roles. Role assignment is explicit and persistent on
 * the entity; this class maps role IDs to behavior.
 */
public final class NpcRoles {
    public static final String RECEPTIONIST = "receptionist";
    public static final String SECRETARY = "secretary";
    public static final String JAILER = "jailer";
    public static final String ARCHIVIST = "archivist";

    private static final Set<String> KNOWN = Set.of(RECEPTIONIST, SECRETARY, JAILER, ARCHIVIST);

    private NpcRoles() {}

    public static boolean isKnown(String roleId) {
        return KNOWN.contains(roleId);
    }

    public static Set<String> knownRoles() {
        return KNOWN;
    }

    public static void interact(String roleId, StrajaNpcEntity npc, Player player, ServerLevel level) {
        switch (roleId == null ? "" : roleId) {
            case RECEPTIONIST -> receptionist(npc, player, level);
            case SECRETARY -> secretary(npc, player, level);
            case JAILER -> jailer(npc, player, level);
            case ARCHIVIST -> archivist(npc, player, level);
            default -> player.sendSystemMessage(Component.literal("Acest NPC nu are un rol Straja configurat."));
        }
    }

    private static com.dwurdy.straja.application.port.out.PlayerGateway gateway(
            Player player, ServerLevel level) {
        return new com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway(
                level.getServer(), player.getUUID());
    }

    private static void receptionist(StrajaNpcEntity npc, Player player, ServerLevel level) {
        // Receptionist: rules + recruitment intake through the real service.
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        var gw = gateway(player, level);
        runtime.guards().showRules(gw);
        runtime.guards().recruit(gw);
    }

    private static void secretary(StrajaNpcEntity npc, Player player, ServerLevel level) {
        // Secretary: mission intake — lists the player's visible missions.
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        var gw = gateway(player, level);
        gw.tell("Secretara Comisarului. Ordinul se scrie cu Carnetul (/straja mission carnet).");
        runtime.missions().list(gw);
    }

    private static void jailer(StrajaNpcEntity npc, Player player, ServerLevel level) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        var gw = gateway(player, level);
        gw.tell("Temnicerul Străjii. Custodia se gestionează prin /straja prison.");
        var store = runtime.context().custody().read();
        gw.tell("În custodie: " + store.cuffed.size() + " catușați, "
                + store.bound.size() + " legați, " + store.downed.size() + " la pământ.");
    }

    private static void archivist(StrajaNpcEntity npc, Player player, ServerLevel level) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        var gw = gateway(player, level);
        gw.tell("Arhivista. Documentele se gestionează prin /straja archive.");
        var archive = runtime.context().archive().read();
        gw.tell("Dosare: " + archive.folders.size() + " | documente: " + archive.sheets.size() + ".");
    }

    public static boolean onHurt(String roleId, StrajaNpcEntity npc, DamageSource source, float amount) {
        // Generic NPCs stay immune. The jailer takes real damage when
        // jailerMayTakeDamage allows it so LivingDamage/Death events fire.
        return !(JAILER.equals(roleId) && jailerMayTakeDamage(npc, source));
    }

    /**
     * True when the source should damage the jailer. Damage must pass for
     * {@code LivingDamageEvent.Post}/{@code LivingDeathEvent} to reach the
     * civic assault service; when {@code jailerGuardImmunity} is on, on-duty
     * guards deal no damage (matching the service-level activeGuard filter).
     */
    public static boolean jailerMayTakeDamage(StrajaNpcEntity npc, DamageSource source) {
        if (!JAILER.equals(npc.getRoleId())) return false;
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null || !(npc.level() instanceof ServerLevel level)) return false;
        boolean onDutyGuard = false;
        if (source.getEntity() instanceof Player player) {
            var state = runtime.players().state(gateway(player, level));
            onDutyGuard = state.duty && state.rank >= 1;
        }
        return runtime.policies().jailerDamageAllowed(onDutyGuard);
    }
}
