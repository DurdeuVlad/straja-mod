package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.AdminRoleplayUseCase;
import com.dwurdy.straja.application.port.in.EmergencyUseCase;
import com.dwurdy.straja.application.port.in.PolicyConfigUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Rank;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * §14 Comisar admin surface behind the Secretary. Composes the personnel,
 * policy and emergency services; all authority checks are re-run here and in
 * the delegated services, so a stale or forged click cannot mutate state.
 */
public class AdminService implements AdminRoleplayUseCase {

    private final StrajaContext ctx;
    private final PlayerService players;
    private final GuardService guards;
    private final PolicyConfigUseCase policies;
    private final EmergencyUseCase emergency;

    public AdminService(StrajaContext ctx, PlayerService players, GuardService guards,
                        PolicyConfigUseCase policies, EmergencyUseCase emergency) {
        this.ctx = ctx;
        this.players = players;
        this.guards = guards;
        this.policies = policies;
        this.emergency = emergency;
    }

    @Override
    public List<AvailableAction> availableActions(PlayerGateway player) {
        if (!authorized(player)) return List.of();
        var actions = new ArrayList<AvailableAction>();
        actions.add(new AvailableAction(Action.PERSONNEL, "", ""));
        actions.add(new AvailableAction(Action.ROSTER_ACTIVE, "", ""));
        actions.add(new AvailableAction(Action.AUTHORIZE, "", ""));
        for (UUID id : ctx.players().knownIds()) {
            GuardState state = ctx.players().read(id);
            if (!isMemberRecord(state)) continue;
            String uuid = id.toString();
            String name = state.lastKnownName != null ? state.lastKnownName
                    : uuid.substring(0, 8);
            actions.add(new AvailableAction(Action.DOSSIER, uuid, name));
            if (state.fired || state.resigned) continue;
            if (state.suspended) {
                actions.add(new AvailableAction(Action.REINSTATE, uuid, name));
                continue;
            }
            if (state.rank < Rank.INSPECTOR.level()) {
                actions.add(new AvailableAction(Action.PROMOTE, uuid, name));
            }
            if (state.rank > Rank.STAGIAR.level()) {
                actions.add(new AvailableAction(Action.DEMOTE, uuid, name));
            }
            actions.add(new AvailableAction(Action.SUSPEND, uuid, name));
            actions.add(new AvailableAction(Action.FIRE, uuid, name));
        }
        actions.add(new AvailableAction(Action.POLICIES, "", ""));
        actions.add(new AvailableAction(Action.POLICY_SET, "", ""));
        actions.add(new AvailableAction(Action.EMERGENCY_STATUS, "", ""));
        actions.add(new AvailableAction(Action.EMERGENCY_ALERT, "", ""));
        var state = ctx.emergency().read();
        actions.add(new AvailableAction(state.active ? Action.EMERGENCY_END : Action.EMERGENCY_START, "", ""));
        return List.copyOf(actions);
    }

    @Override
    public boolean isStillValid(PlayerGateway player, Action action, String memberId) {
        return availableActions(player).stream()
                .anyMatch(a -> a.action() == action && a.memberId().equals(memberId));
    }

    // ------------------------------------------------------------ roster

    @Override
    public void personnel(PlayerGateway actor) {
        if (!gate(actor)) return;
        var rows = roster();
        if (rows.isEmpty()) {
            actor.tell("[Straja] Nu există fișe de personal.");
            return;
        }
        actor.tell("[Straja] Personal înregistrat (" + rows.size() + "):");
        for (UUID id : rows) {
            actor.tell("  " + dossierLine(id));
        }
    }

    @Override
    public void activeRoster(PlayerGateway actor) {
        if (!gate(actor)) return;
        var lines = new ArrayList<String>();
        for (UUID id : ctx.players().knownIds()) {
            GuardState state = ctx.players().read(id);
            if (state.duty) lines.add(dossierLine(id));
        }
        actor.tell(lines.isEmpty() ? "[Straja] Nimeni în serviciu acum."
                : "[Straja] În serviciu (" + lines.size() + "):");
        lines.forEach(l -> actor.tell("  " + l));
    }

    @Override
    public void dossier(PlayerGateway actor, String memberId) {
        if (!gate(actor)) return;
        UUID id = parseUuid(actor, memberId);
        if (id == null) return;
        GuardState state = ctx.players().read(id);
        state.refreshLifecycle();
        actor.tell("[Straja] Dosar " + displayName(state, id) + ":");
        actor.tell("  Rang: " + (state.rank > 0 ? ctx.policies().rankName(state.rank) : "civil")
                + " · Stare: " + state.lifecycle
                + " · Fracțiune: " + (state.nativeFaction != null ? state.nativeFaction : "—")
                + (state.specializations != null && !state.specializations.isEmpty()
                        ? " · Spec.: " + String.join(", ", state.specializations) : ""));
        actor.tell("  Blocuri serviciu: " + state.serviceBlocks
                + " · Sold: " + state.unpaidSalary + " B"
                + (state.duty ? " · ÎN SERVICIU (" + state.mode + ")" : ""));
    }

    // ------------------------------------------------------------ mutations

    @Override
    public void authorize(PlayerGateway actor, String name, int rank) {
        if (!gate(actor)) return;
        PlayerGateway target = ctx.server().findPlayer(name);
        guards.authorizeAt(actor, target, rank);
    }

    @Override
    public void promote(PlayerGateway actor, String memberId) { mutate(actor, memberId, "promote"); }
    @Override
    public void demote(PlayerGateway actor, String memberId) { mutate(actor, memberId, "demote"); }
    @Override
    public void suspend(PlayerGateway actor, String memberId) { mutate(actor, memberId, "suspend"); }
    @Override
    public void fire(PlayerGateway actor, String memberId) { mutate(actor, memberId, "fire"); }
    @Override
    public void reinstate(PlayerGateway actor, String memberId) { mutate(actor, memberId, "reinstate"); }

    // ------------------------------------------------------------ policy & emergency

    @Override
    public void policyList(PlayerGateway actor) { if (gate(actor)) policies.list(actor); }

    @Override
    public void policySet(PlayerGateway actor, String key, String value) {
        if (gate(actor)) policies.set(actor, key, value);
    }

    @Override
    public void emergencyStatus(PlayerGateway actor) { if (gate(actor)) emergency.status(actor); }

    @Override
    public void emergencyAlert(PlayerGateway actor, String message) {
        if (gate(actor)) emergency.alert(actor, message);
    }

    @Override
    public void emergencyStart(PlayerGateway actor, Double multiplier, Integer rounds, String reason) {
        if (gate(actor)) emergency.start(actor, multiplier, rounds, reason);
    }

    @Override
    public void emergencyEnd(PlayerGateway actor) { if (gate(actor)) emergency.end(actor); }

    // ------------------------------------------------------------ helpers

    private void mutate(PlayerGateway actor, String memberId, String op) {
        if (!gate(actor)) return;
        if (!isStillValid(actor, Action.valueOf(op.toUpperCase()), memberId)) {
            actor.tell("[Straja] Acțiunea nu mai este disponibilă pentru acest membru.");
            return;
        }
        PlayerGateway target = ctx.server().findPlayer(memberId);
        if (target == null) {
            actor.tell("[Straja] Membrul nu este online — acțiunile de personal cer prezența.");
            return;
        }
        switch (op) {
            case "promote" -> guards.promote(actor, target);
            case "demote" -> guards.demote(actor, target);
            case "suspend" -> guards.suspend(actor, target);
            case "fire" -> guards.fire(actor, target);
            case "reinstate" -> guards.reinstate(actor, target);
            default -> {}
        }
    }

    private boolean authorized(PlayerGateway player) {
        return players.isCommissioner(player) || player.isOp();
    }

    private boolean gate(PlayerGateway actor) {
        if (authorized(actor)) return true;
        actor.tell("Doar Comisarul poate folosi interfața administrativă.");
        return false;
    }

    private boolean isMemberRecord(GuardState state) {
        state.refreshLifecycle();
        return state.rank > 0 || state.fired || state.resigned || state.suspended
                || state.invited || !"NONE".equals(state.applicationState)
                || !"CIVIL".equals(state.lifecycle);
    }

    private List<UUID> roster() {
        var rows = new ArrayList<UUID>();
        for (UUID id : ctx.players().knownIds()) {
            if (isMemberRecord(ctx.players().read(id))) rows.add(id);
        }
        return rows;
    }

    private String dossierLine(UUID id) {
        GuardState state = ctx.players().read(id);
        state.refreshLifecycle();
        return displayName(state, id) + " — "
                + (state.rank > 0 ? ctx.policies().rankName(state.rank) : "civil")
                + " · " + state.lifecycle + (state.duty ? " · în serviciu" : "");
    }

    private String displayName(GuardState state, UUID id) {
        return state.lastKnownName != null ? state.lastKnownName : id.toString().substring(0, 8);
    }

    private UUID parseUuid(PlayerGateway actor, String memberId) {
        try {
            return UUID.fromString(memberId);
        } catch (Exception e) {
            actor.tell("[Straja] Membru necunoscut: " + memberId);
            return null;
        }
    }
}
