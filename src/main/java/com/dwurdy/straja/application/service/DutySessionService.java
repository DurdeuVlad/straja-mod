package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.DomainEvent;
import com.dwurdy.straja.domain.model.GuardState;

/**
 * Login/logout boundary for native duty sessions.
 *
 * <p>Duty state and checkpoint wall-clock deadlines survive disconnects, but
 * salary accrual does not. This prevents both offline pay and logout-based
 * checkpoint freezing.</p>
 */
public final class DutySessionService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final GuardService guards;
    private final EquipmentService equipment;

    public DutySessionService(StrajaContext ctx, PlayerService players,
                              GuardService guards, EquipmentService equipment) {
        this.ctx = ctx;
        this.players = players;
        this.guards = guards;
        this.equipment = equipment;
    }

    private int salaryFor(GuardState state) {
        return ctx.policies().salaryPerBlock(state.rank);
    }

    /** Persist paid progress up to disconnect, then cut the accrual clock. */
    public void onLogout(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!state.duty) return;

        long now = ctx.clock().nowMillis();
        GuardService.ActivityResult activity = guards.prepareSalaryActivity(player, state, now);
        var events = DutyEngine.accrue(state, activity.accrualNow(), salaryFor(state), ctx.policies());
        notifyEvents(player, events);

        // Critical invariant: reconnect starts a fresh accrual clock. Partial
        // minute/block progress is preserved in dutyRemainderMs/dutyMinutes.
        state.lastAccrualAt = null;
        state.lastDutyActivityAt = null;
        state.lastDutyActivityX = null;
        state.lastDutyActivityY = null;
        state.lastDutyActivityZ = null;
        state.salaryActivityPaused = false;
        players.save(player.uuid(), state);
    }

    /**
     * Resume an active duty without back-paying the offline interval. The duty
     * engine immediately evaluates the absolute checkpoint deadline, so an
     * expired patrol closes on login.
     */
    public DutyEngine.TickResult onLogin(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!state.duty) return new DutyEngine.TickResult(java.util.List.of());

        long now = ctx.clock().nowMillis();
        state.lastAccrualAt = now;
        state.lastDutyActivityAt = now;
        state.lastDutyActivityX = player.x();
        state.lastDutyActivityY = player.y();
        state.lastDutyActivityZ = player.z();
        state.salaryActivityPaused = false;

        var result = DutyEngine.tickDuty(state, now, salaryFor(state),
                ctx.setup().read().missionMinutes, ctx.policies(), now);
        if (!state.duty && state.serviceEquipment != null) {
            equipment.reclaimServiceEquipment(player, state);
        }
        players.save(player.uuid(), state);
        notifyEvents(player, result.events());
        if (state.duty) {
            player.tell("Tura a fost reluată. Timpul offline nu a fost plătit; termenul checkpoint-ului a continuat.");
        }
        return result;
    }

    private void notifyEvents(PlayerGateway player, java.util.List<DomainEvent> events) {
        for (DomainEvent event : events) {
            switch (event.type()) {
                case "salary_block" -> player.tell("Ai acumulat +" + event.data().get("amount")
                        + " bronze în sold.");
                case "checkpoint_available" -> player.tell("Checkpoint disponibil: "
                        + event.data().get("checkpoint") + ". Termenul inițial a continuat și offline.");
                case "duty_ended" -> player.tell("Tura s-a încheiat automat: "
                        + event.data().get("reason") + ". Soldul câștigat online a fost păstrat.");
                default -> {}
            }
        }
    }
}
