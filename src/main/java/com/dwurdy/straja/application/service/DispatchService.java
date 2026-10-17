package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.DispatchCall;
import com.dwurdy.straja.domain.model.DispatchStore;
import com.dwurdy.straja.domain.model.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FUN-6: the dispatch pipeline. Offense events (incidents, BOLOs, fugitive
 * marks, bounties) become calls — targeted, time-bounded work items pushed
 * to on-duty members by rank band and proximity. Calls route attention;
 * the underlying incident/BOLO/wanted records keep their own authority.
 */
public final class DispatchService {
    public static final String SOURCE_INCIDENT = "INCIDENT";
    public static final String SOURCE_BOLO = "BOLO";
    public static final String SOURCE_BOUNTY = "BOUNTY";
    public static final String SOURCE_OFFENSE = "OFFENSE";
    public static final String SOURCE_MANHUNT = "MANHUNT";
    public static final String SOURCE_TRANSFER = "TRANSFER";
    /** FUN-9: escape-prep detections — tunnels, jams, witnessed bribes. */
    public static final String SOURCE_ESCAPE = "ESCAPE";

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private IncidentService incidents; // post-construction: call-accept claims the source incident
    private TransferService transfers; // post-construction: call-accept joins the ride

    public DispatchService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    public void useIncidents(IncidentService incidents) { this.incidents = incidents; }
    public void useTransfers(TransferService transfers) { this.transfers = transfers; }

    private DispatchStore store() {
        DispatchStore data = ctx.dispatch().read();
        data.ensureCalls();
        return data;
    }
    private long now() { return ctx.clock().nowMillis(); }

    /**
     * Raises (or returns the existing live) call for an offense event.
     * Pushes to the band's on-duty members when the severity meets the
     * configured push threshold; below it the call queues on the board.
     */
    public synchronized DispatchCall raise(String sourceType, String sourceId, String title,
                                           String detail, String dimension, double x, double y, double z,
                                           String placeLabel, String subjectUuid, String subjectName,
                                           String createdByUuid,
                                           String severity, String band) {
        DispatchStore data = store();
        for (DispatchCall c : data.calls) {
            if (c != null && c.sourceType.equals(sourceType) && c.sourceId.equals(sourceId)
                    && ("OPEN".equals(c.status) || "ACCEPTED".equals(c.status))) {
                return c; // one live call per offense event
            }
        }
        DispatchCall call = new DispatchCall();
        call.id = data.nextCallId();
        call.sourceType = sourceType == null ? "" : sourceType;
        call.sourceId = sourceId == null ? "" : sourceId;
        call.title = title == null ? "" : title;
        call.detail = detail == null ? "" : detail;
        call.dimension = dimension == null ? "" : dimension;
        call.x = x; call.y = y; call.z = z;
        call.placeLabel = placeLabel == null ? "" : placeLabel;
        call.subjectUuid = subjectUuid == null ? "" : subjectUuid;
        call.subjectName = subjectName == null ? "" : subjectName;
        call.createdByUuid = createdByUuid == null ? "" : createdByUuid;
        call.severity = severity == null ? "ROUTINE" : severity;
        call.band = band == null ? "ENLISTED" : band;
        call.status = "OPEN";
        call.createdAt = now();
        call.bandExpiresAt = now() + ttlMs();
        data.calls.add(call);
        ctx.dispatch().write(data);
        audit.record(AuditActions.INCIDENT_CREATE, "dispatch", "", call.id, call.sourceId,
                AuditResults.SUCCESS, call.sourceType + ":" + call.severity);
        if (pushable(call)) push(call, false);
        return call;
    }

    /** On-duty members currently allowed to answer the call, nearest first. */
    private List<PlayerGateway> eligible(DispatchCall call) {
        List<PlayerGateway> targets = new ArrayList<>();
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p == null || p.uuid() == null || !players.isOnDutyGuard(p)) continue;
            String key = p.uuid().toString();
            if (call.declined.contains(key)) continue;
            // The subject never hunts themselves.
            if (key.equalsIgnoreCase(call.subjectUuid)) continue;
            // A call never demands response across dimensions.
            if (!call.dimension.isBlank() && !call.dimension.equals(p.dimension())) continue;
            var state = players.state(p);
            if (!inBand(call.band, state.rank, p)) continue;
            targets.add(p);
        }
        targets.sort(Comparator.comparingDouble(p ->
                (p.x() - call.x) * (p.x() - call.x) + (p.z() - call.z) * (p.z() - call.z)));
        int cap = Math.max(1, ctx.policies().dispatchMaxPushTargets);
        return targets.size() > cap ? targets.subList(0, cap) : targets;
    }

    private boolean inBand(String band, int rank, PlayerGateway p) {
        // The on-duty commander is above the ladder — every band reaches them.
        if (players.isCommissioner(p.name(), p.uuid())) return true;
        return switch (band) {
            case "ALL" -> true;
            case "OFFICER" -> rank >= Rank.LOCOTENENT.level();
            default -> rank <= Math.max(Rank.STAGIAR.level(), ctx.policies().dispatchEnlistedMaxRank);
        };
    }

    private boolean pushable(DispatchCall call) {
        return severityRank(call.severity) >= policyPushRank();
    }

    private int policyPushRank() {
        // Unknown config values fail quiet — "ROUTINE" pushes everything, so a
        // misspelled threshold must widen, not blanket-push.
        return "ROUTINE".equalsIgnoreCase(ctx.policies().dispatchPushMinSeverity) ? 1 : 2;
    }

    private static int severityRank(String severity) {
        return "URGENT".equalsIgnoreCase(severity) ? 2 : 1;
    }

    private void push(DispatchCall call, boolean escalated) {
        String where = call.placeLabel.isBlank()
                ? String.format(java.util.Locale.ROOT, "(x: %.0f, y: %.0f, z: %.0f)", call.x, call.y, call.z)
                : call.placeLabel;
        for (PlayerGateway p : eligible(call)) {
            String key = p.uuid().toString();
            boolean already = call.offeredTo.contains(key);
            if (!already) call.offeredTo.add(key);
            if (!already || escalated) {
                p.tell("[" + call.severity + "] Apel " + call.id + ": " + call.title + " — " + where
                        + (call.subjectName.isBlank() ? "" : " — subiect: " + call.subjectName)
                        + (escalated && already ? " (escaladat — încă fără răspuns)" : "")
                        + ". Răspunde: /straja dispatch accept " + call.id);
            }
        }
        ctx.dispatch().write(storeWith(call));
    }

    /** Re-reads the store the call belongs to so a caller-held store isn't stale. */
    private DispatchStore storeWith(DispatchCall call) {
        DispatchStore data = store();
        DispatchCall stored = data.find(call.id);
        if (stored != null && stored != call) {
            stored.offeredTo = new ArrayList<>(call.offeredTo);
            stored.declined = new ArrayList<>(call.declined);
            stored.band = call.band;
            stored.status = call.status;
            stored.bandExpiresAt = call.bandExpiresAt;
            stored.acceptedByUuid = call.acceptedByUuid;
            stored.acceptedByName = call.acceptedByName;
            stored.acceptedAt = call.acceptedAt;
        }
        return data;
    }

    /**
     * Periodic sweep (runs under the 20-tick gate): abandoned acceptances
     * re-open, unanswered calls widen through the band ladder then expire,
     * and terminal records past the retention limit are pruned.
     */
    public synchronized void tick() {
        DispatchStore data = store();
        long now = now();
        boolean dirty = false;
        for (DispatchCall call : data.calls) {
            if (call == null) continue;
            if ("ACCEPTED".equals(call.status) && now - call.acceptedAt >= ttlMs()) {
                // The responder went quiet — hand the call back to the board.
                call.status = "OPEN";
                call.acceptedByUuid = "";
                call.acceptedByName = "";
                call.band = "ALL";
                call.bandExpiresAt = now + ttlMs();
                dirty = true;
                ctx.dispatch().write(data);
                if (pushable(call)) push(call, true);
                continue;
            }
            if (!"OPEN".equals(call.status) || now < call.bandExpiresAt) continue;
            dirty = true;
            if (!"ALL".equals(call.band)) {
                call.band = "ALL";
                call.bandExpiresAt = now + ttlMs();
                ctx.dispatch().write(data);
                if (pushable(call)) push(call, true);
                continue;
            }
            call.status = "EXPIRED";
            audit.record(AuditActions.INCIDENT_EXPIRE, "dispatch", "", call.id, call.sourceId,
                    AuditResults.SUCCESS, "unanswered");
            for (PlayerGateway p : ctx.server().onlinePlayers()) {
                if (p != null && players.isCommissioner(p.name(), p.uuid()))
                    p.tell("Apelul " + call.id + " a expirat fără răspuns: " + call.title);
            }
        }
        int retention = Math.max(10, ctx.policies().dispatchRetentionLimit);
        int terminal = 0;
        for (DispatchCall c : data.calls) {
            if (c != null && !"OPEN".equals(c.status) && !"ACCEPTED".equals(c.status)) terminal++;
        }
        int excess = terminal - retention;
        if (excess > 0) {
            // Drop the oldest terminal records first — calls append in order.
            var it = data.calls.iterator();
            while (it.hasNext() && excess > 0) {
                DispatchCall c = it.next();
                if (c != null && !"OPEN".equals(c.status) && !"ACCEPTED".equals(c.status)) {
                    it.remove();
                    excess--;
                }
            }
            dirty = true;
        }
        if (dirty) ctx.dispatch().write(data);
    }

    public synchronized boolean accept(PlayerGateway player, String callId) {
        if (player == null || player.uuid() == null) return false;
        DispatchStore data = store();
        DispatchCall call = data.find(callId);
        if (call == null || !"OPEN".equals(call.status)) {
            player.refuse("straja.dispatch.inactive", "straja.remedy.retry");
            return false;
        }
        if (!players.isOnDutyGuard(player)) {
            player.refuse("straja.incident.work_rank", "straja.remedy.duty");
            return false;
        }
        var state = players.state(player);
        if (!inBand(call.band, state.rank, player)) {
            player.refuse("straja.dispatch.band", "straja.remedy.wait");
            return false;
        }
        if (!call.dimension.isBlank() && !call.dimension.equals(player.dimension())) {
            player.refuse("straja.dispatch.dimension", "straja.remedy.travel");
            return false;
        }
        String key = player.uuid().toString();
        if (key.equalsIgnoreCase(call.subjectUuid)) {
            player.refuse("straja.dispatch.self_subject", "straja.remedy.wait");
            return false;
        }
        if (SOURCE_INCIDENT.equals(call.sourceType) && incidents != null) {
            var incident = ctx.incidents().read().find(call.sourceId);
            if (incident == null || !IncidentService.isActive(incident.status)) {
                call.status = "CLOSED";
                ctx.dispatch().write(data);
                player.refuse("straja.dispatch.inactive", "straja.remedy.retry");
                return false;
            }
            if (!incident.leadGuardUuid.isBlank() && !incident.leadGuardUuid.equals(key)) {
                player.refuse("straja.incident.already_taken", "straja.remedy.retry", incident.leadGuardName);
                return false;
            }
        }
        if (SOURCE_TRANSFER.equals(call.sourceType) && transfers != null
                && transfers.liveTransfer(call.sourceId) == null) {
            call.status = "CLOSED";
            ctx.dispatch().write(data);
            player.refuse("straja.transfer.inactive", "straja.remedy.retry");
            return false;
        }
        call.status = "ACCEPTED";
        call.acceptedByUuid = key;
        call.acceptedByName = player.name();
        call.acceptedAt = now();
        ctx.dispatch().write(data);
        audit.record(AuditActions.INCIDENT_ACCEPT, player.name(), key,
                call.id, call.sourceId, AuditResults.SUCCESS, call.sourceType);
        // Responding to your own report is cleanup, not response work.
        if (!key.equalsIgnoreCase(call.createdByUuid)) grantResponseCredit(player);
        String where = call.placeLabel.isBlank()
                ? String.format(java.util.Locale.ROOT, "x:%.0f y:%.0f z:%.0f", call.x, call.y, call.z)
                : call.placeLabel;
        player.tell("Ai preluat apelul " + call.id + ". Zonă: " + where
                + (call.subjectName.isBlank() ? "" : " — subiect: " + call.subjectName));
        if (SOURCE_INCIDENT.equals(call.sourceType) && incidents != null) {
            incidents.accept(player, call.sourceId); // claim the source too
        }
        if (SOURCE_TRANSFER.equals(call.sourceType) && transfers != null) {
            transfers.callAccepted(player, call.sourceId); // join the ride
        }
        return true;
    }

    public synchronized boolean decline(PlayerGateway player, String callId) {
        if (player == null || player.uuid() == null) return false;
        if (!players.isOnDutyGuard(player)) {
            player.refuse("straja.incident.work_rank", "straja.remedy.duty");
            return false;
        }
        DispatchStore data = store();
        DispatchCall call = data.find(callId);
        if (call == null || !"OPEN".equals(call.status)) return false;
        String key = player.uuid().toString();
        if (call.declined.contains(key)) return true; // idempotent
        call.offeredTo.remove(key);
        call.declined.add(key);
        ctx.dispatch().write(data);
        audit.record(AuditActions.INCIDENT_LEAVE, player.name(), key, call.id, "",
                AuditResults.SUCCESS, "declined");
        player.tell("Ai refuzat apelul " + call.id + ".");
        return true;
    }

    /** Resolves every live call raised from the given source record. */
    public synchronized int closeForSource(String sourceType, String sourceId, String outcome) {
        DispatchStore data = store();
        int closed = 0;
        String action = switch (sourceType == null ? "" : sourceType) {
            case SOURCE_BOLO -> AuditActions.BOLO_RESOLVE;
            case SOURCE_BOUNTY -> AuditActions.BOUNTY_POST;
            default -> AuditActions.INCIDENT_RESOLVE;
        };
        for (DispatchCall call : data.calls) {
            if (call == null || !call.sourceType.equals(sourceType) || !call.sourceId.equals(sourceId)
                    || (!"OPEN".equals(call.status) && !"ACCEPTED".equals(call.status))) continue;
            call.status = "CLOSED";
            closed++;
            audit.record(action, "dispatch", "", call.id, call.sourceId,
                    AuditResults.SUCCESS, outcome == null ? "" : outcome);
        }
        if (closed > 0) ctx.dispatch().write(data);
        return closed;
    }

    /** The dispatch board: open calls plus accepted work in flight. Members only. */
    public synchronized List<DispatchCall> board() {
        List<DispatchCall> live = new ArrayList<>();
        for (DispatchCall call : store().calls) {
            if (call != null && ("OPEN".equals(call.status) || "ACCEPTED".equals(call.status)))
                live.add(call);
        }
        live.sort(Comparator.comparing((DispatchCall c) -> "URGENT".equals(c.severity) ? 0 : 1)
                .thenComparingLong(c -> c.createdAt));
        return live;
    }

    private long ttlMs() {
        return Math.max(1, ctx.policies().dispatchCallTtlMinutes) * 60_000L;
    }

    private void grantResponseCredit(PlayerGateway guard) {
        int minutes = ctx.policies().dutyResponseCreditMinutes;
        if (minutes <= 0 || guard == null || guard.uuid() == null) return;
        var state = players.state(guard);
        if (state == null || !state.duty) return;
        long until = now() + minutes * 60_000L;
        if (state.responseCreditUntil == null || state.responseCreditUntil < until) {
            state.responseCreditUntil = until;
            players.save(guard.uuid(), state);
        }
    }
}
