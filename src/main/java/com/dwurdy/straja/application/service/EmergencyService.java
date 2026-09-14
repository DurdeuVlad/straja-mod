package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.EmergencyUseCase;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.EmergencyState;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;

/**
 * §25 emergency system. Two mechanisms share one persisted state: a TTL-bound
 * urgency call broadcast to every online member (and delivered to late logins),
 * and a sustained emergency mode that multiplies hourly wages and requires
 * extra patrol rounds. Authority: the Comisar or an operator/console.
 */
public class EmergencyService implements EmergencyUseCase {

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public EmergencyService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    @Override
    public void alert(PlayerGateway actor, String message) {
        if (!authorized(actor)) return;
        if (message == null || message.isBlank()) {
            actor.tell("Folosire: /straja emergency alert <mesaj>.");
            return;
        }
        EmergencyState state = ctx.emergency().read();
        state.urgencyMessage = message.trim();
        state.urgencyIssuedAt = now();
        state.urgencyIssuedBy = actor.name();
        ctx.emergency().write(state);
        broadcast(urgencyText(state));
        actor.tell("[Straja] Urgență emisă (" + ctx.policies().emergencyUrgencyTtlMinutes
                + " min): " + state.urgencyMessage);
        audit.record("emergency_alert", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(), "SUCCESS", state.urgencyMessage);
    }

    @Override
    public void clearUrgency(PlayerGateway actor) {
        if (!authorized(actor)) return;
        EmergencyState state = ctx.emergency().read();
        if (!state.urgencyLive(now(), ctx.policies().emergencyUrgencyTtlMinutes)) {
            actor.tell("Nu există o urgență activă.");
            return;
        }
        state.clearUrgency();
        ctx.emergency().write(state);
        broadcast("[Straja] Urgența a fost ridicată.");
        audit.record("emergency_clear", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(), "SUCCESS", "");
    }

    @Override
    public void start(PlayerGateway actor, Double payMultiplier, Integer rounds, String reason) {
        if (!authorized(actor)) return;
        EmergencyState state = ctx.emergency().read();
        if (state.active) {
            actor.tell("Starea de urgență este deja activă (×" + state.payMultiplier
                    + " plată, " + state.requiredRounds + " runde obligatorii).");
            return;
        }
        var policies = ctx.policies();
        double multiplier = payMultiplier != null ? payMultiplier : policies.emergencyPayMultiplier;
        if (multiplier < 1.0) multiplier = 1.0;
        if (multiplier > policies.emergencyMaxPayMultiplier) multiplier = policies.emergencyMaxPayMultiplier;
        int laps = rounds != null ? rounds : policies.emergencyPatrolRounds;
        laps = Math.max(1, Math.min(laps, policies.emergencyMaxPatrolRounds));

        state.active = true;
        state.payMultiplier = multiplier;
        state.requiredRounds = laps;
        state.reason = reason == null || reason.isBlank() ? null : reason.trim();
        state.startedAt = now();
        state.startedBy = actor.name();
        ctx.emergency().write(state);
        broadcast("[Straja] STARE DE URGENȚĂ activată de " + actor.name()
                + (state.reason != null ? " — " + state.reason : "")
                + ". Plată de pericol ×" + state.payMultiplier
                + "; patrulele noi cer " + state.requiredRounds + " runde complete.");
        actor.tell("[Straja] Stare de urgență activă: ×" + state.payMultiplier
                + " plată, " + state.requiredRounds + " runde/patrulă.");
        audit.record("emergency_start", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(), "SUCCESS",
                "multiplier=" + state.payMultiplier + ";rounds=" + state.requiredRounds
                        + (state.reason != null ? ";reason=" + state.reason : ""));
    }

    @Override
    public void end(PlayerGateway actor) {
        if (!authorized(actor)) return;
        EmergencyState state = ctx.emergency().read();
        if (!state.active) {
            actor.tell("Starea de urgență nu este activă.");
            return;
        }
        state.clearEmergency();
        ctx.emergency().write(state);
        broadcast("[Straja] Starea de urgență a fost încheiată. Plata revine la tariful normal.");
        actor.tell("[Straja] Stare de urgență încheiată. Soldurile acumulate rămân neschimbate.");
        audit.record("emergency_end", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(), "SUCCESS", "");
    }

    @Override
    public void status(PlayerGateway actor) {
        EmergencyState state = ctx.emergency().read();
        long now = now();
        if (state.urgencyLive(now, ctx.policies().emergencyUrgencyTtlMinutes)) {
            long left = state.urgencyIssuedAt + ctx.policies().emergencyUrgencyTtlMinutes * 60_000L - now;
            actor.tell("[Straja] Urgență activă (" + Math.max(1, left / 60_000L) + " min rămase): "
                    + state.urgencyMessage);
        } else {
            actor.tell("[Straja] Nicio urgență activă.");
        }
        if (state.active) {
            actor.tell("[Straja] Stare de urgență ACTIVĂ — plată ×" + state.payMultiplier
                    + ", " + state.requiredRounds + " runde/patrulă"
                    + (state.reason != null ? ", motiv: " + state.reason : "") + ".");
        } else {
            actor.tell("[Straja] Starea de urgență nu este activă.");
        }
    }

    @Override
    public void deliverUrgency(PlayerGateway player) {
        if (!isMember(player)) return;
        EmergencyState state = ctx.emergency().read();
        if (state.urgencyLive(now(), ctx.policies().emergencyUrgencyTtlMinutes)) {
            player.tell(urgencyText(state));
        }
    }

    // ------------------------------------------------------------ helpers

    private long now() { return ctx.clock().nowMillis(); }

    private boolean authorized(PlayerGateway actor) {
        if (players.isCommissioner(actor) || actor.isOp()) return true;
        actor.tell("Doar Comisarul sau un operator poate gestiona starea de urgență.");
        return false;
    }

    private boolean isMember(PlayerGateway player) {
        GuardState state = players.state(player);
        return players.isCommissioner(player)
                || (state.rank >= Rank.STAGIAR.level() && !state.fired && !state.resigned);
    }

    private void broadcast(String message) {
        for (PlayerGateway online : ctx.server().onlinePlayers()) {
            if (isMember(online)) online.tell(message);
        }
    }

    private String urgencyText(EmergencyState state) {
        var hq = ctx.setup().read().location(SetupData.HQ);
        return "[Straja] URGENȚĂ: " + state.urgencyMessage
                + (hq != null ? " Prezentați-vă de urgență la sediul Străjii." : "");
    }
}
