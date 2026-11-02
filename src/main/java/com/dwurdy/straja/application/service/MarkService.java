package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.BountyStatus;
import com.dwurdy.straja.domain.model.BountyStore;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The law records crimes without a clerk. A player killing another player
 * outside lawful contexts gets the record written for them — an incident
 * naming the crime scene plus a system BOLO naming the killer — and a wanted
 * or fugitive player nobody prices receives a state-posted bounty
 * ("emisa de Straja") after a configurable grace window. Everything is
 * config-gated and idempotent: the mark outlives, it never stacks.
 *
 * <p>Lawful contexts stay exempt (§15a): a wanted target still at large is
 * already huntable by anyone — killing them is the bounty game, not a new
 * crime — and an on-duty guard's lawful-force kill (authoritative task,
 * hostile, unrestrained target) is enforcement, never a mark.
 */
public class MarkService {
    private static final String AUDIT_ACTION = "auto_mark";
    private static final long SWEEP_INTERVAL_MS = 10_000L;
    private static final double WITNESS_RADIUS = 40.0;
    private static final String SYSTEM_ISSUER = "Straja";

    private final StrajaContext ctx;
    private final AuditService audit;
    private final IncidentService incidents;
    private final BoloService bolos;
    private final BountyService bounties;
    private final WantedService wanted;
    private final ReputationService reputation;
    /** First sweep sighting per wanted uuid — the bounty grace clock.
     *  In-memory on purpose: a restart re-opens the grace window, which is
     *  the conservative direction (delays a bounty, never duplicates one). */
    private final Map<String, Long> wantedSince = new LinkedHashMap<>();
    /** Uuids whose state bounty an Inspector cancelled during this wanted
     *  episode — the sweep must never repost over a human decision. Cleared
     *  when the wanted episode ends, so a later mark prices normally. */
    private final Set<String> bountySuppressed = new HashSet<>();
    private long lastSweep;

    public MarkService(StrajaContext ctx, AuditService audit,
                       IncidentService incidents, BoloService bolos,
                       BountyService bounties, WantedService wanted,
                       ReputationService reputation) {
        this.ctx = ctx;
        this.audit = audit;
        this.incidents = incidents;
        this.bolos = bolos;
        this.bounties = bounties;
        this.wanted = wanted;
        this.reputation = reputation;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private static String uuidOf(PlayerGateway p) {
        return p.uuid() == null ? "" : p.uuid().toString();
    }

    /**
     * Hooked on {@code recordFinalDeath}: a PvP kill outside lawful contexts
     * writes incident + system BOLO naming the killer. Idempotent — a player
     * already wanted needs no second mark, though each victim still gets a
     * case file.
     */
    public void recordKill(PlayerGateway killer, PlayerGateway victim) {
        if (!ctx.policies().autoMarkKills || killer == null || victim == null) return;
        if (killer.uuid() != null && killer.uuid().equals(victim.uuid())) return;
        // §15a fair game: the victim was wanted AND still at large — bounty
        // capture and wanted-on-sight land here. A cuffed or escorted wanted
        // prisoner is already caught; killing them is murder, not the game.
        if (wanted.isWanted(victim.uuid()) && !reputation.isRestrained(victim)
                && !wanted.isUnderEscort(victim)) return;
        // §15a lawful force: on-duty guard + authoritative task + recently
        // hostile + unrestrained — the same predicate reputation uses, so a
        // lawful kill is never simultaneously rewarded and prosecuted.
        if (reputation.isLawfulForceKill(killer, victim)) return;
        if (ctx.policies().autoMarkWitnessOnly && !witnessed(killer, victim)) return;
        var incident = incidents.createAutoMark(killer, victim);
        // The mark outlives the kill: an already-marked killer stays marked;
        // only the BOLO suppresses, the victim's case file still lands.
        var bolo = wanted.isWanted(killer.uuid()) ? null
                : bolos.flagSystem(uuidOf(killer), killer.name(),
                        "uciderea lui " + victim.name());
        killer.tellKey("straja.mark.recorded", victim.name());
        audit.record(AUDIT_ACTION, killer.name(), uuidOf(killer),
                victim.name(), uuidOf(victim), AuditResults.SUCCESS,
                "incident=" + (incident == null ? "" : incident.id)
                        + " bolo=" + (bolo == null ? "suppressed" : bolo.id));
    }

    /** A third player inside witness range makes the kill public record. */
    private boolean witnessed(PlayerGateway killer, PlayerGateway victim) {
        for (var p : ctx.server().onlinePlayers()) {
            if (p.uuid() == null || p.uuid().equals(killer.uuid())
                    || p.uuid().equals(victim.uuid())) continue;
            if (!p.dimension().equals(killer.dimension())) continue;
            double dx = p.x() - killer.x(), dy = p.y() - killer.y(),
                    dz = p.z() - killer.z();
            if (dx * dx + dy * dy + dz * dz <= WITNESS_RADIUS * WITNESS_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Periodic sweep: a wanted or fugitive player nobody priced gets the
     * state bounty after {@code autoBountyAfterHours} of marked time. The
     * mark's first sweep sighting starts the grace clock. Runs unattended
     * inside the server tick funnel — a failure here must never abort the
     * ticks that follow it.
     */
    public void tick() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // The sweep is a notification layer — never drop the tick funnel.
        }
    }

    private void sweep() {
        if (!ctx.policies().autoBountyEnabled) return;
        long now = now();
        if (now - lastSweep < SWEEP_INTERVAL_MS) return;
        lastSweep = now;
        var wantedSet = wanted.wantedUuids();
        wantedSince.keySet().retainAll(wantedSet);
        bountySuppressed.retainAll(wantedSet);
        long graceMs = Math.max(0, ctx.policies().autoBountyAfterHours) * 3_600_000L;
        int amount = Math.max(ctx.policies().bountyMinAmount,
                Math.min(ctx.policies().autoBountyAmount,
                        ctx.policies().bountyMaxAmount));
        for (String uuid : wantedSet) {
            if (bountySuppressed.contains(uuid)) continue;
            long since = wantedSince.computeIfAbsent(uuid, k -> now);
            if (now - since < graceMs) continue;
            var store = ctx.bounties().read();
            if (store.activeFor(uuid) != null) continue;
            if (systemBountyCancelled(store, uuid, since)) {
                bountySuppressed.add(uuid);
                continue;
            }
            bounties.postSystem(uuid, nameFor(uuid), amount, "emisa de Straja");
        }
    }

    /** A state bounty cancelled inside this wanted episode must not repost. */
    private static boolean systemBountyCancelled(BountyStore store, String uuid, long since) {
        if (store == null || store.records == null) return false;
        for (var r : store.records) {
            if (r != null && r.status == BountyStatus.CANCELLED
                    && uuid.equals(r.targetUuid) && SYSTEM_ISSUER.equals(r.postedBy)
                    && r.postedAt >= since) return true;
        }
        return false;
    }

    /** Best-known display name for a wanted uuid: online, then BOLO subject. */
    private String nameFor(String uuid) {
        for (var p : ctx.server().onlinePlayers()) {
            if (p.uuid() != null && uuid.equals(p.uuid().toString())) return p.name();
        }
        for (var bolo : bolos.active()) {
            if (bolo != null && uuid.equals(bolo.subjectUuid)
                    && bolo.subjectName != null && !bolo.subjectName.isBlank()) {
                return bolo.subjectName;
            }
        }
        return uuid;
    }
}
