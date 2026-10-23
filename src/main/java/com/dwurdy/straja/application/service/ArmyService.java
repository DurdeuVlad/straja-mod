package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.ArmyRepository;
import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.PersonnelRepository;
import com.dwurdy.straja.domain.model.ArmyOperation;
import com.dwurdy.straja.domain.model.ArmyStore;
import com.dwurdy.straja.domain.model.ArmyUnit;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The army half of the institution: standing units plus scheduled operations.
 * Every military-track member (enlisted or officer) enlists at the Comandant
 * desk — the station unit self-organizes on first enlistment, the first
 * officer-grade member takes command. Operations are three-click affairs for
 * the player (sign up, report) and fully automatic otherwise: the tick starts
 * scheduled ops, completes active ones, pays every reported participant
 * service blocks through the same {@code serviceBlocks} wallet missions use,
 * moves unit readiness by turnout, and queues the next operation — a unit
 * with members never sits idle waiting for an admin.
 *
 * <p>All mutations are synchronized and the store revision bumps on every
 * write — same contract as the other V2 services.
 */
public final class ArmyService {
    /** Delay between scheduling and muster — sign-up window. */
    public static final long MUSTER_DELAY_MS = 5L * 60_000L;
    /** How long an operation stays ACTIVE for members to report. */
    public static final long ACTIVE_WINDOW_MS = 15L * 60_000L;
    /** Cadence between auto-scheduled operations for an active unit. */
    public static final long AUTO_INTERVAL_MS = 60L * 60_000L;
    /** Completed operations retained per unit for the history view. */
    public static final int HISTORY_LIMIT = 20;
    private static final int READINESS_FLOOR = 0;
    private static final int READINESS_CEILING = 100;
    private static final int READINESS_FAIL_DROP = 8;
    private static final int READINESS_BASE_GAIN = 3;
    private static final int READINESS_PER_PARTICIPANT = 3;
    private static final int READINESS_MAX_GAIN = 12;
    private static final Logger LOGGER = LoggerFactory.getLogger(ArmyService.class);

    private final ArmyRepository repository;
    private final PersonnelRepository personnel;
    private final Clock clock;
    private final IdGenerator ids;
    private BiConsumer<String, Integer> awardHook = (uuid, blocks) -> {};

    public ArmyService(ArmyRepository repository, PersonnelRepository personnel,
                       Clock clock, IdGenerator ids) {
        this.repository = repository;
        this.personnel = personnel;
        this.clock = clock;
        this.ids = ids;
    }

    /** Installs the service-block payer after composition (like MissionV2). */
    public void useAwardHook(BiConsumer<String, Integer> hook) {
        this.awardHook = hook == null ? (uuid, blocks) -> {} : hook;
    }

    // ---------------------------------------------------------------- enlist

    /**
     * Joins the caller's home-station unit, creating it on first enlistment.
     * Officers take vacant command automatically. One unit per member.
     */
    public synchronized ArmyUnit enlist(String playerUuid) {
        PersonnelRecord person = requireMilitary(playerUuid);
        ArmyStore store = repository.read();
        String existing = store.ensureMemberIndex().get(playerUuid);
        if (existing != null) {
            ArmyUnit unit = store.ensureUnits().get(existing);
            if (unit != null && unit.isMember(playerUuid)) return unit;
            store.ensureMemberIndex().remove(playerUuid);
        }
        ArmyUnit unit = unitForStation(store, person.homeStationId);
        if (unit == null) {
            unit = new ArmyUnit();
            unit.unitId = ids.newId("UNI");
            unit.name = "Garda " + (person.homeStationId == null || person.homeStationId.isBlank()
                    ? "hq" : person.homeStationId);
            unit.stationId = person.homeStationId == null || person.homeStationId.isBlank()
                    ? "hq" : person.homeStationId;
            unit.createdAt = clock.nowMillis();
            store.ensureUnits().put(unit.unitId, unit);
        }
        unit.ensureMembers().put(playerUuid, clock.nowMillis());
        store.ensureMemberIndex().put(playerUuid, unit.unitId);
        if (unit.commanderUuid.isBlank() && person.careerGrade != null && person.careerGrade.isOfficer()) {
            unit.commanderUuid = playerUuid;
        }
        unit.version++;
        store.storeRevision++;
        repository.write(store);
        return unit;
    }

    public synchronized boolean leave(String playerUuid) {
        ArmyStore store = repository.read();
        String unitId = store.ensureMemberIndex().get(playerUuid);
        if (unitId == null) return false;
        removeMember(store, unitId, playerUuid);
        store.storeRevision++;
        repository.write(store);
        return true;
    }

    /** Drops a player from a unit roster and every open-op roster slot. */
    private void removeMember(ArmyStore store, String unitId, String playerUuid) {
        store.ensureMemberIndex().remove(playerUuid);
        ArmyUnit unit = store.ensureUnits().get(unitId);
        if (unit != null) {
            if (unit.ensureMembers().remove(playerUuid) != null) {
                unit.version++;
            }
            if (playerUuid.equals(unit.commanderUuid)) {
                unit.commanderUuid = successor(unit, store);
                unit.version++;
            }
        }
        for (ArmyOperation op : store.ensureOperations().values()) {
            if (op != null && (op.status == ArmyOperation.Status.SCHEDULED
                    || op.status == ArmyOperation.Status.ACTIVE)) {
                if (op.ensureSignups().remove(playerUuid)
                        | op.ensureParticipants().remove(playerUuid)) {
                    op.version++;
                }
            }
        }
    }

    /** Next officer member when the commander steps down (deterministic order). */
    private String successor(ArmyUnit unit, ArmyStore store) {
        var records = personnel.read().ensureRecords();
        for (String memberUuid : unit.ensureMembers().keySet()) {
            PersonnelRecord member = records.get(memberUuid);
            if (member != null && member.active() && member.careerGrade != null
                    && member.careerGrade.isOfficer()) {
                return memberUuid;
            }
        }
        return "";
    }

    // ------------------------------------------------------------- muster ops

    /**
     * Officer scheduling from the desk: actor must be an officer-grade active
     * member of the target unit. Ops always open a sign-up window first.
     */
    public synchronized ArmyOperation schedule(String actorUuid, String unitId,
                                               ArmyOperation.Kind kind, long startDelayMs,
                                               long durationMs) {
        ArmyStore store = repository.read();
        ArmyUnit unit = requireUnit(store, unitId);
        PersonnelRecord actor = personnel.read().ensureRecords().get(actorUuid);
        if (actor == null || !actor.active()) {
            throw new IllegalStateException("STRAJA_MEMBER_REQUIRED");
        }
        if (actor.careerGrade == null || !actor.careerGrade.isOfficer()
                || !unit.isMember(actorUuid)) {
            throw new IllegalStateException("DENIED_ARMY_COMMAND");
        }
        if (hasOpenOp(store, unit.unitId)) {
            throw new IllegalStateException("OP_ALREADY_OPEN");
        }
        return create(store, unit, kind, startDelayMs, durationMs, actorUuid);
    }

    /** Admin/auto path — the caller (command tree or tick) holds the authority. */
    public synchronized ArmyOperation scheduleAsAuthority(String unitId,
                                               ArmyOperation.Kind kind, long startDelayMs,
                                               long durationMs, String source) {
        ArmyStore store = repository.read();
        ArmyUnit unit = requireUnit(store, unitId);
        return create(store, unit, kind, startDelayMs, durationMs, source);
    }

    private ArmyOperation create(ArmyStore store, ArmyUnit unit, ArmyOperation.Kind kind,
                                 long startDelayMs, long durationMs, String source) {
        if (kind == null) throw new IllegalArgumentException("operation kind required");
        long now = clock.nowMillis();
        ArmyOperation op = new ArmyOperation();
        op.operationId = ids.newId("OPS");
        op.unitId = unit.unitId;
        op.kind = kind;
        op.scheduledStart = now + Math.max(0, startDelayMs);
        op.scheduledEnd = op.scheduledStart + Math.max(30_000L, durationMs);
        op.scheduledBy = source == null ? "" : source;
        op.createdAt = now;
        store.ensureOperations().put(op.operationId, op);
        unit.version++;
        store.storeRevision++;
        repository.write(store);
        return op;
    }

    /** Member signs up for the unit's next scheduled operation. */
    public synchronized ArmyOperation signup(String playerUuid) {
        requireMilitary(playerUuid);
        ArmyStore store = repository.read();
        ArmyUnit unit = memberUnit(store, playerUuid);
        ArmyOperation op = nextSignup(store, unit.unitId);
        if (op == null) throw new IllegalStateException("NO_OPERATION_SCHEDULED");
        if (!op.ensureSignups().add(playerUuid)) throw new IllegalStateException("ALREADY_SIGNED");
        op.version++;
        store.storeRevision++;
        repository.write(store);
        return op;
    }

    /**
     * Member reports to the active muster — walk-ups without a signup count
     * too, the muster roll only cares who showed. One participant record.
     */
    public synchronized ArmyOperation report(String playerUuid) {
        requireMilitary(playerUuid);
        ArmyStore store = repository.read();
        ArmyUnit unit = memberUnit(store, playerUuid);
        ArmyOperation op = activeOp(store, unit.unitId);
        if (op == null) throw new IllegalStateException("NO_OPERATION_ACTIVE");
        boolean changed = op.ensureParticipants().add(playerUuid);
        changed |= op.ensureSignups().add(playerUuid);
        if (changed) {
            op.version++;
            store.storeRevision++;
            repository.write(store);
        }
        return op;
    }

    // ------------------------------------------------------------------- tick

    /**
     * Background sweep on the 20-tick block: keeps every populated unit on
     * the operations treadmill (auto-schedule → start → complete → award →
     * next). All transitions are status-latched single-fires.
     */
    public synchronized int tick() {
        ArmyStore store = repository.read();
        long now = clock.nowMillis();
        int changed = 0;
        var payouts = new ArrayList<String[]>();

        // Membership follows personnel status: suspended/terminated/resigned
        // members fall off the roster (and every open-op slot) on the next
        // sweep instead of lingering as phantom strength.
        var records = personnel.read().ensureRecords();
        for (ArmyUnit unit : store.ensureUnits().values()) {
            if (unit == null || unit.ensureMembers().isEmpty()) continue;
            for (String memberUuid : new ArrayList<>(unit.ensureMembers().keySet())) {
                PersonnelRecord member = records.get(memberUuid);
                if (member == null || !member.active()) {
                    removeMember(store, unit.unitId, memberUuid);
                    changed++;
                }
            }
        }

        for (ArmyUnit unit : store.ensureUnits().values()) {
            if (unit == null || unit.ensureMembers().isEmpty()) continue;
            if (!hasOpenOp(store, unit.unitId) && now >= unit.nextAutoAt) {
                ArmyOperation.Kind kind = ArmyOperation.Kind.values()[
                        Math.floorMod(unit.nextAutoKind, ArmyOperation.Kind.values().length)];
                unit.nextAutoKind++;
                unit.nextAutoAt = now + AUTO_INTERVAL_MS;
                unit.version++;
                create(store, unit, kind, MUSTER_DELAY_MS, ACTIVE_WINDOW_MS, "AUTO");
                changed++;
            }
        }

        for (ArmyOperation op : store.ensureOperations().values()) {
            if (op == null) continue;
            if (op.status == ArmyOperation.Status.SCHEDULED && now >= op.scheduledStart) {
                op.status = ArmyOperation.Status.ACTIVE;
                op.version++;
                changed++;
            }
            if (op.status == ArmyOperation.Status.ACTIVE && now >= op.scheduledEnd) {
                complete(store, op, now, records, payouts);
                changed++;
            }
        }

        if (changed > 0) {
            prune(store);
            repository.write(store);
        }
        // The latched COMPLETED status is already on disk — a hook failure or
        // retry can never double-pay because the op can never re-complete.
        for (String[] payout : payouts) {
            try {
                awardHook.accept(payout[0], Integer.parseInt(payout[1]));
            } catch (RuntimeException hookError) {
                LOGGER.warn("army award hook failed for {}", payout[0], hookError);
            }
        }
        return changed;
    }

    /**
     * Latch the outcome once: turnout decides success, readiness moves, and
     * the reported participants who still hold active membership are queued
     * for their service-block payout after the store write.
     */
    private void complete(ArmyStore store, ArmyOperation op, long now,
                          java.util.Map<String, PersonnelRecord> records,
                          List<String[]> payouts) {
        ArmyUnit unit = store.ensureUnits().get(op.unitId);
        int turnout = op.ensureParticipants().size();
        int signed = op.ensureSignups().size();
        if (turnout == 0) {
            op.status = ArmyOperation.Status.FAILED;
            op.resultNote = "Nicio prezentare — operație eșuată";
            if (unit != null) {
                unit.readiness = clamp(unit.readiness - READINESS_FAIL_DROP);
                unit.operationsFailed++;
                unit.version++;
            }
        } else {
            op.status = ArmyOperation.Status.COMPLETED;
            op.awardedBlocks = op.kind.rewardBlocks;
            op.resultNote = turnout + "/" + Math.max(turnout, signed) + " prezenți";
            if (unit != null) {
                int gain = Math.min(READINESS_MAX_GAIN,
                        READINESS_BASE_GAIN + turnout * READINESS_PER_PARTICIPANT);
                unit.readiness = clamp(unit.readiness + gain);
                unit.operationsCompleted++;
                unit.version++;
            }
            for (String participant : op.ensureParticipants()) {
                PersonnelRecord member = records.get(participant);
                if (member != null && member.active()) {
                    payouts.add(new String[]{participant, Integer.toString(op.awardedBlocks)});
                }
            }
        }
        op.completedAt = now;
        op.version++;
    }

    /** Keeps the newest {@link #HISTORY_LIMIT} finished ops per unit. */
    private void prune(ArmyStore store) {
        var byUnit = new java.util.HashMap<String, List<ArmyOperation>>();
        for (ArmyOperation op : store.ensureOperations().values()) {
            if (op != null && (op.status == ArmyOperation.Status.COMPLETED
                    || op.status == ArmyOperation.Status.FAILED
                    || op.status == ArmyOperation.Status.CANCELLED)) {
                byUnit.computeIfAbsent(op.unitId, key -> new ArrayList<>()).add(op);
            }
        }
        for (List<ArmyOperation> finished : byUnit.values()) {
            if (finished.size() <= HISTORY_LIMIT) continue;
            finished.sort(Comparator.comparingLong(o -> o.completedAt));
            for (int i = 0; i < finished.size() - HISTORY_LIMIT; i++) {
                store.ensureOperations().remove(finished.get(i).operationId);
            }
        }
    }

    // ------------------------------------------------------------------ views

    /** The player's unit, or null. */
    public synchronized ArmyUnit unitFor(String playerUuid) {
        ArmyStore store = repository.read();
        String unitId = store.ensureMemberIndex().get(playerUuid);
        return unitId == null ? null : store.ensureUnits().get(unitId);
    }

    /** Next op needing action: ACTIVE first (report now), else soonest SCHEDULED. */
    public synchronized ArmyOperation nextOpFor(String unitId) {
        ArmyStore store = repository.read();
        ArmyOperation active = activeOp(store, unitId);
        return active != null ? active : nextSignup(store, unitId);
    }

    public synchronized List<ArmyOperation> historyFor(String unitId, int limit) {
        ArmyStore store = repository.read();
        return store.ensureOperations().values().stream()
                .filter(op -> op != null && unitId.equals(op.unitId)
                        && (op.status == ArmyOperation.Status.COMPLETED
                        || op.status == ArmyOperation.Status.FAILED))
                .sorted(Comparator.comparingLong((ArmyOperation o) -> o.completedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized List<ArmyUnit> all() {
        ArmyStore store = repository.read();
        return List.copyOf(store.ensureUnits().values());
    }

    public synchronized ArmyUnit findUnit(String unitId) {
        return repository.read().ensureUnits().get(unitId);
    }

    /** The station's standing unit — the public view non-members get. */
    public synchronized ArmyUnit unitForStationId(String stationId) {
        ArmyStore store = repository.read();
        return unitForStation(store, stationId);
    }

    public synchronized ArmyOperation findOperation(String operationId) {
        return repository.read().ensureOperations().get(operationId);
    }

    /** Admin cancel — a SCHEDULED or ACTIVE op is stood down, no result. */
    public synchronized ArmyOperation cancel(String operationId) {
        ArmyStore store = repository.read();
        ArmyOperation op = store.ensureOperations().get(operationId);
        if (op == null) throw new IllegalArgumentException("unknown operation: " + operationId);
        if (op.status != ArmyOperation.Status.SCHEDULED
                && op.status != ArmyOperation.Status.ACTIVE) {
            throw new IllegalStateException("OP_NOT_OPEN");
        }
        op.status = ArmyOperation.Status.CANCELLED;
        op.completedAt = clock.nowMillis();
        op.version++;
        store.storeRevision++;
        repository.write(store);
        return op;
    }

    /** Admin disband — stands down open ops, frees the roster, removes the unit. */
    public synchronized boolean disband(String unitId) {
        ArmyStore store = repository.read();
        ArmyUnit unit = store.ensureUnits().get(unitId);
        if (unit == null) return false;
        long now = clock.nowMillis();
        for (ArmyOperation op : store.ensureOperations().values()) {
            if (op != null && unitId.equals(op.unitId)
                    && (op.status == ArmyOperation.Status.SCHEDULED
                    || op.status == ArmyOperation.Status.ACTIVE)) {
                op.status = ArmyOperation.Status.CANCELLED;
                op.completedAt = now;
                op.version++;
            }
        }
        for (String member : unit.ensureMembers().keySet()) {
            store.ensureMemberIndex().remove(member);
        }
        store.ensureUnits().remove(unitId);
        store.storeRevision++;
        repository.write(store);
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private PersonnelRecord requireMilitary(String playerUuid) {
        PersonnelRecord person = personnel.read().ensureRecords().get(playerUuid);
        if (person == null || !person.active()) {
            throw new IllegalStateException("STRAJA_MEMBER_REQUIRED");
        }
        if (person.careerGrade != null && person.careerGrade.isProfessional()) {
            throw new IllegalStateException("MILITARY_TRACK_REQUIRED");
        }
        return person;
    }

    private ArmyUnit unitForStation(ArmyStore store, String stationId) {
        String station = stationId == null || stationId.isBlank() ? "hq" : stationId;
        ArmyUnit found = null;
        for (ArmyUnit unit : store.ensureUnits().values()) {
            if (unit == null || !station.equals(unit.stationId)) continue;
            if (found == null || unit.createdAt < found.createdAt) found = unit;
        }
        return found;
    }

    private ArmyUnit memberUnit(ArmyStore store, String playerUuid) {
        String unitId = store.ensureMemberIndex().get(playerUuid);
        ArmyUnit unit = unitId == null ? null : store.ensureUnits().get(unitId);
        if (unit == null || !unit.isMember(playerUuid)) {
            if (unitId != null) {
                store.ensureMemberIndex().remove(playerUuid);
                store.storeRevision++;
                repository.write(store);
            }
            throw new IllegalStateException("NOT_IN_UNIT");
        }
        return unit;
    }

    private boolean hasOpenOp(ArmyStore store, String unitId) {
        for (ArmyOperation op : store.ensureOperations().values()) {
            if (op != null && unitId.equals(op.unitId)
                    && (op.status == ArmyOperation.Status.SCHEDULED
                    || op.status == ArmyOperation.Status.ACTIVE)) {
                return true;
            }
        }
        return false;
    }

    private ArmyOperation nextSignup(ArmyStore store, String unitId) {
        ArmyOperation found = null;
        for (ArmyOperation op : store.ensureOperations().values()) {
            if (op != null && unitId.equals(op.unitId)
                    && op.status == ArmyOperation.Status.SCHEDULED
                    && (found == null || op.scheduledStart < found.scheduledStart)) {
                found = op;
            }
        }
        return found;
    }

    private ArmyOperation activeOp(ArmyStore store, String unitId) {
        for (ArmyOperation op : store.ensureOperations().values()) {
            if (op != null && unitId.equals(op.unitId)
                    && op.status == ArmyOperation.Status.ACTIVE) {
                return op;
            }
        }
        return null;
    }

    private static ArmyUnit requireUnit(ArmyStore store, String unitId) {
        ArmyUnit unit = store.ensureUnits().get(unitId);
        if (unit == null) throw new IllegalArgumentException("unknown unit: " + unitId);
        return unit;
    }

    private static int clamp(int value) {
        return Math.max(READINESS_FLOOR, Math.min(READINESS_CEILING, value));
    }
}
