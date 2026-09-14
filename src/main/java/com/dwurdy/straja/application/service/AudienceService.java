package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.AudienceUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AudienceRequest;
import com.dwurdy.straja.domain.model.AudienceStore;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayList;
import java.util.List;

/**
 * §12 audience requests: members file through the Secretary; the Comisar is
 * notified online (coalesced — one tell per cooldown, not per request) and
 * decides at the same surface. Decided outcomes reach the requester on their
 * next status check or login.
 */
public class AudienceService implements AudienceUseCase {
    private static final int REASON_LIMIT = 500;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public AudienceService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private StrajaPolicies p() {
        return ctx.policies();
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    private boolean isMember(PlayerGateway player) {
        var state = players.state(player);
        return players.isCommissioner(player)
                || (state.rank >= Rank.STAGIAR.level() && !state.fired && !state.resigned);
    }

    @Override
    public List<AvailableAction> availableActions(PlayerGateway player) {
        if (!isMember(player)) return List.of();
        var actions = new ArrayList<AvailableAction>();
        actions.add(new AvailableAction(Action.REQUEST, ""));
        actions.add(new AvailableAction(Action.STATUS, ""));
        if (players.isCommissioner(player)) {
            actions.add(new AvailableAction(Action.REVIEW_LIST, ""));
            for (AudienceRequest r : ctx.audiences().read().pending()) {
                actions.add(new AvailableAction(Action.REVIEW, r.id));
            }
        }
        return List.copyOf(actions);
    }

    @Override
    public boolean request(PlayerGateway player, String reason) {
        if (!isMember(player)) {
            player.tell("Doar membrii Străjii pot cere audiență la Comisar.");
            return false;
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isBlank()) {
            player.tell("Cererea de audiență are nevoie de un motiv.");
            return false;
        }
        if (text.length() > REASON_LIMIT) text = text.substring(0, REASON_LIMIT);
        long now = now();
        String uuid = player.uuid().toString();
        AudienceStore store = ctx.audiences().read();
        AudienceRequest request = store.openFor(uuid);
        boolean updated = request != null;
        if (request == null) {
            request = new AudienceRequest();
            request.id = "A" + store.nextId++;
            request.requesterUuid = uuid;
            request.requesterName = player.name();
            request.createdAt = now;
            store.requests.put(request.id, request);
        }
        request.reason = text;
        request.updatedAt = now;
        request.status = AudienceRequest.PENDING;
        // Coalesced Comisar notification: one tell per cooldown window.
        store.unnotifiedCount++;
        if (now - store.lastComisarNotifiedAt >= p().audienceNotifyCooldownSeconds * 1000L) {
            notifyComisar(store.unnotifiedCount);
            store.unnotifiedCount = 0;
            store.lastComisarNotifiedAt = now;
        }
        ctx.audiences().write(store);
        audit.record("audience_request", player.name(), uuid, request.id, uuid, "SUCCESS",
                updated ? "reason_updated" : "filed");
        player.tell(updated
                ? "Motivul cererii " + request.id + " a fost actualizat."
                : "Cererea de audiență " + request.id + " a fost trimisă Comisarului.");
        return true;
    }

    @Override
    public void status(PlayerGateway player) {
        if (!isMember(player)) {
            player.tell("Doar membrii Străjii au cereri de audiență.");
            return;
        }
        String uuid = player.uuid().toString();
        AudienceStore store = ctx.audiences().read();
        AudienceRequest open = store.openFor(uuid);
        if (open != null) {
            player.tell("Cererea " + open.id + " așteaptă decizia Comisarului: \"" + open.reason + "\"");
        }
        AudienceRequest decided = store.latestDecidedFor(uuid);
        if (decided == null) {
            if (open == null) player.tell("Nu ai nicio cerere de audiență.");
        } else {
            deliverOutcome(store, decided, player);
        }
        ctx.audiences().write(store);
    }

    @Override
    public void listForReview(PlayerGateway player) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' vede cererile de audiență.");
            return;
        }
        var pending = ctx.audiences().read().pending();
        if (pending.isEmpty()) {
            player.tell("Nu există cereri de audiență în așteptare.");
            return;
        }
        player.tell("Cereri de audiență în așteptare: " + pending.size());
        for (AudienceRequest r : pending) {
            player.tell("  " + r.id + " — " + r.requesterName + ": \"" + r.reason + "\"");
        }
    }

    @Override
    public boolean resolve(PlayerGateway player, String id, String decision, String note) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' decide cererile de audiență.");
            return false;
        }
        AudienceStore store = ctx.audiences().read();
        AudienceRequest request = id == null ? null : store.requests.get(id.trim());
        if (request == null || !AudienceRequest.PENDING.equals(request.status)) {
            player.tell("Cererea " + id + " nu așteaptă o decizie.");
            return false;
        }
        String normalized = decision == null ? "" : decision.trim().toLowerCase();
        String status = switch (normalized) {
            case "resolve", "rezolva", "rezolvă", "accept", "accepta", "acceptă" -> AudienceRequest.RESOLVED;
            case "dismiss", "respinge", "reject" -> AudienceRequest.DISMISSED;
            default -> null;
        };
        if (status == null) {
            player.tell("Decizie necunoscută — folosește resolve sau dismiss.");
            return false;
        }
        request.status = status;
        request.resolvedBy = player.name();
        request.resolutionNote = note == null ? "" : note.trim();
        request.resolvedAt = now();
        request.outcomeDelivered = false;
        ctx.audiences().write(store);
        audit.record("audience_review", player.name(), player.uuid().toString(),
                request.id, request.requesterUuid, "SUCCESS", status);
        player.tell(AudienceRequest.RESOLVED.equals(status)
                ? "Cererea " + request.id + " a fost rezolvată."
                : "Cererea " + request.id + " a fost respinsă.");
        // If the requester is online, tell them now instead of waiting for login.
        var requester = ctx.server().findPlayer(request.requesterUuid);
        if (requester != null) {
            AudienceStore fresh = ctx.audiences().read();
            AudienceRequest current = fresh.requests.get(request.id);
            if (current != null) deliverOutcome(fresh, current, requester);
            ctx.audiences().write(fresh);
        }
        return true;
    }

    @Override
    public void deliverOutcome(PlayerGateway player) {
        AudienceStore store = ctx.audiences().read();
        AudienceRequest decided = store.latestDecidedFor(player.uuid().toString());
        if (decided != null && !decided.outcomeDelivered) {
            deliverOutcome(store, decided, player);
            ctx.audiences().write(store);
        }
    }

    private void deliverOutcome(AudienceStore store, AudienceRequest request,
                                PlayerGateway requester) {
        String verdict = AudienceRequest.RESOLVED.equals(request.status)
                ? "rezolvată" : "respinsă";
        requester.tell("[Straja] Cererea ta de audiență " + request.id + " a fost " + verdict
                + " de Comisar."
                + (request.resolutionNote.isBlank() ? "" : " Notă: " + request.resolutionNote));
        request.outcomeDelivered = true;
    }

    private void notifyComisar(int newCount) {
        for (PlayerGateway online : ctx.server().onlinePlayers()) {
            if (players.isCommissioner(online)) {
                online.tell("[Straja] " + newCount + " cereri de audiență noi așteaptă la Secretariat.");
            }
        }
    }
}
