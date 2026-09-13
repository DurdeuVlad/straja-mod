package com.dwurdy.straja.adapter.in.npc;

import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/**
 * Registry of Straja NPC roles. Role assignment is explicit and persistent on
 * the entity; this class only routes interactions into application services.
 */
public final class NpcRoles {
    public static final String RECEPTIONIST = "receptionist";
    public static final String RECRUITER = "recruiter";
    public static final String TRAINER = "trainer";
    public static final String SECRETARY = "secretary";
    public static final String JAILER = "jailer";
    public static final String ARCHIVIST = "archivist";

    private static final Set<String> KNOWN = Set.of(
            RECEPTIONIST, RECRUITER, TRAINER, SECRETARY, JAILER, ARCHIVIST);

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
            case RECRUITER -> recruiter(npc, player, level);
            case TRAINER -> trainer(npc, player, level);
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
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        var gw = gateway(player, level);
        runtime.guards().showRules(gw);
        runtime.personnel().submitApplication(gw);
    }

    private static void recruiter(StrajaNpcEntity npc, Player player, ServerLevel level) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        runtime.personnel().recruiterIntake(gateway(player, level));
    }

    private static void trainer(StrajaNpcEntity npc, Player player, ServerLevel level) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        runtime.personnel().trainerIntake(gateway(player, level));
    }

    private static void secretary(StrajaNpcEntity npc, Player player, ServerLevel level) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        var gw = gateway(player, level);

        if (runtime.players().isCommissioner(gw)) {
            gw.tell("Secretariatul Comisarului. Cereri și rapoarte în așteptare:");
            var pending = runtime.personnel().pendingPersonnelInbox(gw);
            if (pending.isEmpty()) gw.tell("— nimic în așteptare —");
            for (int i = 0; i < Math.min(8, pending.size()); i++) {
                var item = pending.get(i);
                gw.tell(item.id + " | " + item.type + " | " + item.sender + " | " + item.text);
            }
            if (pending.size() > 8) gw.tell("... și încă " + (pending.size() - 8) + " intrări.");
            gw.tell("Administrare: /straja-personnel inbox | authorize | review | resolve-audience");
            runtime.missions().list(gw);
            return;
        }

        var state = runtime.personnel().ensurePersonnelRecord(gw);
        if (!state.authorized()) {
            gw.tell("Secretariatul deservește personalul autorizat al Străjii. Pentru recrutare mergi la Recepție.");
            return;
        }

        // Native interim interaction until the dedicated client menu lands:
        // normal right-click toggles the shift; sneak-right-click is the
        // administrative counter (status + salary collection + report hints).
        if (player.isShiftKeyDown()) {
            runtime.guards().salary(gw);
            state = runtime.personnel().ensurePersonnelRecord(gw);
            gw.tell("Secretariat: " + state.serviceNumber + " | "
                    + com.dwurdy.straja.domain.model.Rank.of(state.rank).displayName());
            gw.tell("Tură: " + (state.duty ? "ACTIVĂ" : "INACTIVĂ")
                    + " | sold neîncasat: " + state.unpaidSalary + " bronze.");
            if (runtime.personnel().activityReportDue(gw)) {
                gw.tell("Raportul săptămânal este SCADENT. Folosește /straja-personnel report <text>.");
            } else if (state.nextActivityReportDueAt != null) {
                gw.tell("Următorul raport: " + runtime.guards().prettyTime(state.nextActivityReportDueAt) + ".");
            }
            gw.tell("Audiență: /straja-personnel audience <motiv>");
            runtime.missions().list(gw);
            return;
        }

        if (state.duty) {
            runtime.guards().stopDuty(gw);
            gw.tell("Tura voluntară se încheie la Secretariat. Facțiunea principală va fi restaurată.");
        } else if (runtime.personnel().canStartDuty(gw)) {
            runtime.guards().startDuty(gw);
        }
    }

    private static void jailer(StrajaNpcEntity npc, Player player, ServerLevel level) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return;
        var gw = gateway(player, level);
        gw.tell("Temnicerul Străjii. Custodia se gestionează prin /straja prison.");
        var store = runtime.context().custody().read();
        gw.tell("În custodie: " + store.cuffed.size() + " cătușați, "
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
        return !(JAILER.equals(roleId) && jailerMayTakeDamage(npc, source));
    }

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
