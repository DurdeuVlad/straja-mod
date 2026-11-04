package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditResults;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INST-4 — Patrulier: the institution's physical force when no human officer
 * is on duty. A bound patrol officer scans its post radius for wanted
 * players, engages the nearest until it can apply restraints through
 * {@link CustodyService}, and books the prisoner into the nearest free
 * station cell through {@link PrisonService} — an institutional transport,
 * not a player escort ride.
 *
 * <p>All decisions run here against the {@link Officer} port so the logic is
 * fully testable headless; the entity goal only adapts the live NPC into the
 * port. Stand-down follows the escort truth: restrained, escorted or
 * cleared targets are already caught and are never engaged.
 */
public class PatrolService {
    private static final String AUDIT_ACTION = "patrol_book";
    private static final String SYSTEM_ISSUER = "Straja";
    /** Arm's reach: inside this distance the officer restrains the suspect. */
    private static final double CUFF_RANGE = 2.5;
    /** Inside this distance the officer strikes a resisting suspect. */
    private static final double MELEE_RANGE = 3.0;
    /** Below this health the officer never swings — restrain, don't execute. */
    private static final double MELEE_STOP_HEALTH = 6.0;
    /** The officer keeps station within this distance of its post. */
    private static final double POST_SETTLE = 3.0;

    /**
     * The live NPC adapter — every call the service can make on the patrol
     * officer. Headless tests substitute a recording fake.
     */
    public interface Officer {
        String entityUuid();
        String dimension();
        double x();
        double y();
        double z();
        double postX();
        double postY();
        double postZ();
        double distanceTo(PlayerGateway target);
        void moveTo(PlayerGateway target);
        void moveToPost();
        void melee(PlayerGateway target);
        void stopMoving();
    }

    private final StrajaContext ctx;
    private final AuditService audit;
    private final WantedService wanted;
    private final CustodyService custody;
    private final PrisonService prison;
    private final ReputationService reputation;
    private DispatchService dispatch;

    /** officer entity uuid -> currently engaged target uuid. */
    private final Map<String, String> engagement = new LinkedHashMap<>();
    /** officer entity uuid -> last strike/cuff attempt (cooldown clock). */
    private final Map<String, Long> lastAction = new LinkedHashMap<>();
    /** officer entity uuid -> cuffed prisoner held for a free cell. */
    private final Map<String, String> holding = new LinkedHashMap<>();

    public PatrolService(StrajaContext ctx, AuditService audit,
                         WantedService wanted, CustodyService custody,
                         PrisonService prison, ReputationService reputation) {
        this.ctx = ctx;
        this.audit = audit;
        this.wanted = wanted;
        this.custody = custody;
        this.prison = prison;
        this.reputation = reputation;
    }

    public void useDispatch(DispatchService service) { this.dispatch = service; }

    /** The officer entity left the world — its beat state dies with it. */
    public void release(String officerUuid) {
        engagement.remove(officerUuid);
        lastAction.remove(officerUuid);
        holding.remove(officerUuid);
    }

    private long now() { return ctx.clock().nowMillis(); }

    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }

    private PlayerGateway online(String uuid) {
        for (var p : ctx.server().onlinePlayers()) {
            if (p.uuid() != null && uuid.equals(p.uuid().toString())) return p;
        }
        return null;
    }

    /**
     * One decision beat for a bound patrol officer — driven by the entity
     * goal at a few ticks' cadence. Never throws into the caller: the NPC
     * tick path is unattended infrastructure.
     */
    public void tick(Officer officer) {
        try {
            beat(officer);
        } catch (RuntimeException ignored) {
            // A patrol tick must never break the entity's goal loop.
        }
    }

    private void beat(Officer officer) {
        if (officer == null || !ctx.policies().patrolOfficerEnabled) return;
        String id = officer.entityUuid();
        if (holdPost(officer, id)) return;
        PlayerGateway target = engagement.containsKey(id)
                ? online(engagement.get(id)) : null;
        if (target != null && !huntable(target, officer)) {
            engagement.remove(id);
            target = null;
        }
        if (target == null) {
            target = selectTarget(officer);
            if (target != null) engagement.put(id, uuidOf(target));
        }
        if (target == null) {
            returnToPost(officer);
            return;
        }
        long now = now();
        if (now - lastAction.getOrDefault(id, 0L) < engageCooldownMs()) {
            officer.moveTo(target);
            return;
        }
        if (officer.distanceTo(target) <= CUFF_RANGE) {
            book(officer, target);
            lastAction.put(id, now);
            return;
        }
        // The officer strikes only a suspect that can take the hit — a
        // wanted player is restrained, never executed on the street. Inside
        // the band a weak suspect is closed on and cuffed instead.
        if (officer.distanceTo(target) <= MELEE_RANGE
                && target.health() > MELEE_STOP_HEALTH) {
            officer.melee(target);
            lastAction.put(id, now);
        }
        officer.moveTo(target);
    }

    /**
     * A prisoner booked with no free cell stays cuffed under the officer's
     * watch until the waitlist delivers a bunk or custody otherwise ends.
     * Returns true while the officer is still holding.
     */
    private boolean holdPost(Officer officer, String id) {
        String heldId = holding.get(id);
        if (heldId == null) return false;
        PlayerGateway held = online(heldId);
        // A waitlisted prisoner is already seized — the marks resolved when
        // they were booked — so wantedness is deliberately not the release
        // condition. The officer holds until a bunk claims the prisoner,
        // the restraint ends, the booking is cancelled, or the prisoner
        // goes offline.
        if (held == null || !reputation.isRestrained(held)
                || cellAssigned(heldId) || !sentenceWaiting(heldId)) {
            holding.remove(id);
            return false;
        }
        officer.stopMoving();
        return true;
    }

    private boolean cellAssigned(String targetUuid) {
        var data = ctx.prison().read();
        for (var a : data.assignments.values()) {
            if (a != null && targetUuid.equals(a.targetUuid)) return true;
        }
        return false;
    }

    /** A pending transport exists: the held prisoner still waits for a bunk. */
    private boolean sentenceWaiting(String targetUuid) {
        var data = ctx.prison().read();
        for (var s : data.sentences) {
            if (s != null && targetUuid.equals(s.targetUuid)
                    && "WAITING_CELL".equals(s.status)) return true;
        }
        return false;
    }

    /** The nearest huntable wanted player inside the post's jurisdiction. */
    private PlayerGateway selectTarget(Officer officer) {
        double postR = Math.max(1, ctx.policies().patrolPostRadius);
        double scanR = Math.max(1, ctx.policies().patrolScanRange);
        PlayerGateway best = null;
        double bestSq = Double.MAX_VALUE;
        for (var p : ctx.server().onlinePlayers()) {
            if (!huntable(p, officer)) continue;
            double postD = distSq(p, officer.postX(), officer.postY(), officer.postZ());
            double officerD = distSq(p, officer.x(), officer.y(), officer.z());
            if (postD <= postR * postR && officerD <= scanR * scanR && postD < bestSq) {
                bestSq = postD;
                best = p;
            }
        }
        return best;
    }

    /**
     * Huntable = wanted, not yet caught, not escorted, same dimension, and
     * still inside the engagement region — the post's jurisdiction plus the
     * officer's scan range beyond it. A suspect that escapes the region is
     * left to the mark and the bounty hunters.
     */
    private boolean huntable(PlayerGateway t, Officer officer) {
        if (t == null || t.uuid() == null) return false;
        if (!wanted.isWanted(t.uuid())) return false;
        if (reputation.isRestrained(t) || wanted.isUnderEscort(t)) return false;
        if (!officer.dimension().equals(t.dimension())) return false;
        // MASK-1: a covered face beats casual recognition — the officer must
        // close in to see through it. Marks and BOLOs are untouched; only
        // the hunt is fooled.
        if (ctx.policies().maskEnabled && isMasked(t)
                && officer.distanceTo(t)
                        > Math.max(0.5, ctx.policies().maskRecognitionRadius)) {
            return false;
        }
        double leash = Math.max(1, ctx.policies().patrolPostRadius)
                + Math.max(1, ctx.policies().patrolScanRange);
        return distSq(t, officer.postX(), officer.postY(), officer.postZ())
                <= leash * leash;
    }

    private static double distSq(PlayerGateway p, double x, double y, double z) {
        double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** A full-face cover from the policy mask set hides the player's identity. */
    private boolean isMasked(PlayerGateway t) {
        var head = t.headItem();
        return head != null && !head.isEmpty()
                && ctx.policies().maskItemIds != null
                && ctx.policies().maskItemIds.contains(head.id());
    }

    /**
     * Contact: restraints first, then the institutional booking. When no
     * cell is free the prisoner is seized into the waitlist and the officer
     * holds station over them while dispatch carries the call.
     */
    private void book(Officer officer, PlayerGateway target) {
        String id = officer.entityUuid();
        var cuffs = custody.applyCuffsSystem(target, "patrol_arrest");
        if (!cuffs.ok()) {
            audit.record(AUDIT_ACTION, SYSTEM_ISSUER, id, target.name(),
                    uuidOf(target), AuditResults.REFUSED,
                    "cuffs:" + cuffs.reason());
            return;
        }
        var sentence = prison.arrestNearest(target, 0,
                "arest în teren — patrulier");
        if (sentence == null) {
            // The booking itself was refused — no pending sentence exists to
            // hold over, so the officer does not pretend a transport is due.
        } else if ("WAITING_CELL".equals(sentence.status)) {
            raiseNoCellCall(officer, target);
            holding.put(id, uuidOf(target));
        }
        engagement.remove(id);
        audit.record(AUDIT_ACTION, SYSTEM_ISSUER, id, target.name(),
                uuidOf(target), AuditResults.SUCCESS,
                sentence == null ? "booking_failed"
                        : "sentence=" + sentence.id + " status=" + sentence.status);
    }

    private void raiseNoCellCall(Officer officer, PlayerGateway target) {
        if (dispatch == null) return;
        try {
            dispatch.raise(DispatchService.SOURCE_PATROL, officer.entityUuid(),
                    "Prizonier reținut: " + target.name(),
                    "nicio celulă liberă — ofițerul de patrulă păzește prizonierul",
                    officer.dimension(), officer.x(), officer.y(), officer.z(), "",
                    uuidOf(target), target.name(), "", "URGENT", "ENLISTED");
        } catch (RuntimeException e) {
            // Dispatch is a notification layer — never drop the booking.
        }
    }

    private void returnToPost(Officer officer) {
        double dx = officer.x() - officer.postX();
        double dy = officer.y() - officer.postY();
        double dz = officer.z() - officer.postZ();
        if (dx * dx + dy * dy + dz * dz > POST_SETTLE * POST_SETTLE) {
            officer.moveToPost();
        } else {
            officer.stopMoving();
        }
    }

    private long engageCooldownMs() {
        return Math.max(1, ctx.policies().patrolEngageCooldownSeconds) * 1_000L;
    }
}
