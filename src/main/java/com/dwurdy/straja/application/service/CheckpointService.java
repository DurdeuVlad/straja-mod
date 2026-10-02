package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.CrossingDirection;
import com.dwurdy.straja.domain.model.CrossingOutcome;
import com.dwurdy.straja.domain.model.GateLane;
import com.dwurdy.straja.domain.model.InspectionLedgerEntry;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.LawCheckpointStore;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.SnapshotItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LAW-002 checkpoint core: port of the prototype's 5-tick crossing scan
 * ({@code cpGateScan}), stage pipelines ({@code cpInspect}/{@code cpArrest}),
 * boarding-zone check ({@code cpBoardScan}), wrong-way deny, and ledger
 * logging ({@code cpLogEvent}). Movement state is per-session like
 * {@code cpGatePrev}; boarding stamps are session-scoped ({@code cpBoard}).
 */
public final class CheckpointService {
    private static final int SCAN_EVERY = 5;          // ticks between scans
    private static final double MAX_STEP = 14.0;      // teleport/jump guard
    private static final double GATE_MARGIN = 3.0;    // cpGateNear slack
    private static final long BOARD_STAMP_MS = 20L * 60 * 1000; // 20-minute stamp
    private static final String DENY_SOUND = "minecraft:block.iron_door.close";
    private static final String WARN_SOUND = "minecraft:block.note_block.bass";

    private final StrajaContext ctx;
    private final AuditService audit;
    private final PrisonService prison;
    private final StorageService storage;
    private final BoloService bolos;

    private final Map<UUID, PrevPos> prevPositions = new ConcurrentHashMap<>();
    // Deliberate deviation: stamps are session-scoped, not persisted like the
    // prototype's cpBoard persistentData — a relog forces a fresh dock check
    // rather than trusting a 20-minute window across disconnects.
    private final Map<UUID, BoardingStamp> boardStamps = new ConcurrentHashMap<>();

    private record PrevPos(String dim, double x, double y, double z) {}
    private record BoardingStamp(String siteId, long expiresAt) {}

    public CheckpointService(StrajaContext ctx, AuditService audit,
                             PrisonService prison, StorageService storage, BoloService bolos) {
        this.ctx = ctx;
        this.audit = audit;
        this.prison = prison;
        this.storage = storage;
        this.bolos = bolos;
    }

    /** Server-tick entry: prototype cadence is every 5 ticks. */
    public void tick() {
        if (ctx.server().tickCount() % SCAN_EVERY != 0) return;
        LawCheckpointStore store = ctx.lawCheckpoints().read();
        if (store.checkpoints().isEmpty()) return;
        for (PlayerGateway player : ctx.server().onlinePlayers()) {
            try {
                scanPlayer(player, store);
            } catch (RuntimeException ignored) {
                // one bad player never stops the scan (prototype parity)
            }
        }
    }

    /** Clears per-session crossing/board state (login + logout). */
    public void clearPlayer(UUID uuid) {
        prevPositions.remove(uuid);
        boardStamps.remove(uuid);
    }

    // ------------------------------------------------------------ scan

    private void scanPlayer(PlayerGateway p, LawCheckpointStore store) {
        String dim = p.dimension();
        double x = p.x(), y = p.y(), z = p.z();
        PrevPos prev = prevPositions.get(p.uuid());

        // Boarding is position-state, not a crossing: a rider inside the dock
        // zone is checked even on first sighting (prototype cpBoardScan had no
        // prev dependency). An arrest here mutates position — stop scanning.
        for (var site : store.checkpoints().values()) {
            if (site == null || !site.active || isExempt(p, site, store)) continue;
            if (site.boardZone != null && p.ridingBoatLike()
                    && site.boardZone.contains(dim, x, y, z)
                    && boardCheck(p, site, store)) {
                reseed(p);
                return;
            }
        }

        prevPositions.put(p.uuid(), new PrevPos(dim, x, y, z));
        if (prev == null || !prev.dim().equals(dim)) return;
        String gm = p.gameModeName();
        if (!"survival".equals(gm) && !"adventure".equals(gm)) return;

        double mx = x - prev.x(), mz = z - prev.z();
        if (mx == 0 && mz == 0 && y == prev.y()) return;            // no movement
        boolean bigStep = mx * mx + mz * mz > MAX_STEP * MAX_STEP;  // teleport jump

        for (var site : store.checkpoints().values()) {
            if (site == null || !site.active || isExempt(p, site, store)) continue;

            // Gate lanes only evaluate walking crossings — a teleport step is
            // not a "walked through the gate" event. Stage boxes still run:
            // entering an arrest box by teleport is still an entry.
            if (!bigStep) {
                for (GateLane lane : site.gates) {
                    if (!lane.dimension().equals(dim)) continue;
                    if (!lane.near(prev.x(), prev.z(), x, z, GATE_MARGIN)
                            || !lane.crosses(prev.x(), prev.z(), x, z)) continue;
                    if (lane.sideOf(prev.x(), prev.z()) == 0) break; // ambiguous origin — ignore
                    if (lane.wrongWay(prev.x(), prev.z())) {
                        gateDeny(p, site, prev);
                        reseed(p);
                        return;
                    }
                    if (site.linkedCheckpointId != null && !site.linkedCheckpointId.isBlank()
                            && rightWayArrival(p, site, store)) {
                        reseed(p);
                        return;
                    }
                    break;
                }
            }
            if (handleStages(p, site, store, prev, x, y, z)) {
                reseed(p); // arrested/pushed back — re-seed at the post-effect position
                return;
            }
        }
    }

    /** Re-seeds prev-position at the player's actual (post-teleport) spot. */
    private void reseed(PlayerGateway p) {
        prevPositions.put(p.uuid(), new PrevPos(p.dimension(), p.x(), p.y(), p.z()));
    }

    // ------------------------------------------------------------ stages

    /** Returns true when the stage pipeline arrested or pushed the player back. */
    private boolean handleStages(PlayerGateway p, LawCheckpointRecord site,
                                 LawCheckpointStore store, PrevPos prev, double x, double y, double z) {
        String dim = p.dimension();
        if (site.direction != CrossingDirection.BIDIRECTIONAL) {
            CrossingDirection dir = directionOf(site, prev, dim);
            if (dir != null && dir != site.direction) return false; // unpoliced direction
        }
        boolean entered1 = entered(site.stage1, dim, prev, x, y, z);
        boolean entered2 = entered(site.stage2, dim, prev, x, y, z);
        if (!entered1 && !entered2) return false;
        if (site.mode == com.dwurdy.straja.domain.model.CheckpointMode.DENY) {
            return denyStage(p, site, store, dir(site, prev, dim));
        }
        return entered2 ? arrestStage2(p, site, store, dir(site, prev, dim))
                        : inspect(p, site, store, dir(site, prev, dim));
    }

    private static boolean entered(com.dwurdy.straja.domain.model.LawBounds bounds,
                                   String dim, PrevPos prev, double x, double y, double z) {
        return bounds != null
                && bounds.contains(dim, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                && !bounds.contains(dim, (int) Math.floor(prev.x()), (int) Math.floor(prev.y()), (int) Math.floor(prev.z()));
    }

    /** Direction estimate from the first in-dimension lane; null when none exists. */
    private CrossingDirection directionOf(LawCheckpointRecord site, PrevPos prev, String dim) {
        for (GateLane lane : site.gates) {
            if (!lane.dimension().equals(dim)) continue;
            return lane.wrongWay(prev.x(), prev.z())
                    ? CrossingDirection.INCOMING : CrossingDirection.OUTGOING;
        }
        return null;
    }

    private CrossingDirection dir(LawCheckpointRecord site, PrevPos prev, String dim) {
        CrossingDirection d = directionOf(site, prev, dim);
        return d != null ? d
                : site.direction != null ? site.direction : CrossingDirection.BIDIRECTIONAL;
    }

    // ------------------------------------------------------------ pipelines (prototype order preserved)

    /** Stage 1 — banned push back, custody/hunted arrest on sight, contraband warns, clean pass. */
    private boolean inspect(PlayerGateway p, LawCheckpointRecord site,
                            LawCheckpointStore store, CrossingDirection dir) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        if (isBanned(p, site, store)) {
            p.title("straja.checkpoint.deny_title", "straja.checkpoint.banned_sub");
            pushback(p, site);
            ledger(site, p, dir, CrossingOutcome.DENY, snapshot, found, "interzis (ban)");
            return true;
        }
        if (inCustody(p)) {
            arrest(p, site, "fugitiv prins la punctul de control", snapshot, found, dir);
            return true;
        }
        if (isHunted(p)) {
            // AT6: wanted on-sight at ANY arresting gate — clean pockets don't protect.
            arrest(p, site, "vânat de Straja prins la frontieră", snapshot, found, dir);
            return true;
        }
        if (found.isEmpty()) {
            ledger(site, p, dir, CrossingOutcome.PASS, snapshot, found, "curat");
            p.tellKey("straja.checkpoint.clean");
            return false;
        }
        ledger(site, p, dir, CrossingOutcome.WARN, snapshot, found, "contraband la etapa 1");
        p.title("straja.checkpoint.warn_title", "straja.checkpoint.warn_sub");
        playSound(p, WARN_SOUND);
        p.tellKey("straja.checkpoint.contraband_header");
        for (String line : found) p.tell("§7- §f" + line);
        p.tellKey("straja.checkpoint.contraband_footer");
        return false;
    }

    /** Stage 2 — arrest assessment: custody > hunted > banned > contraband; clean logs 'out'. */
    private boolean arrestStage2(PlayerGateway p, LawCheckpointRecord site,
                                 LawCheckpointStore store, CrossingDirection dir) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        boolean banned = isBanned(p, site, store);
        boolean jailed = inCustody(p);
        boolean hunted = isHunted(p);
        if (!banned && !jailed && !hunted && found.isEmpty()) {
            ledger(site, p, dir, CrossingOutcome.PASS, snapshot, found, "curat — a trecut frontiera");
            return false;
        }
        String reason = jailed ? "fugitiv prins la punctul de control"
                : hunted ? "vânat de Straja prins la frontieră"
                : banned ? "interdicție la punctul de control (ban activ)"
                : "marfă interzisă la frontieră";
        return arrest(p, site, reason, snapshot, found, dir);
    }

    /** DENY-mode stage entry — violators repelled (wanted logged as sighting), clean pass. */
    private boolean denyStage(PlayerGateway p, LawCheckpointRecord site,
                              LawCheckpointStore store, CrossingDirection dir) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        String violation = null;
        if (isBanned(p, site, store)) violation = "interzis (ban)";
        else if (inCustody(p)) violation = "deținut la poartă";
        else if (!found.isEmpty()) violation = "marfă interzisă";
        else if (isHunted(p)) violation = "vânat reperat la poartă";
        if (violation == null) {
            ledger(site, p, dir, CrossingOutcome.PASS, snapshot, found, "curat");
            return false;
        }
        p.title("straja.checkpoint.deny_title", "straja.checkpoint.deny_sub");
        pushback(p, site);
        ledger(site, p, dir, CrossingOutcome.DENY, snapshot, found, violation);
        return true;
    }

    /** Wrong-way lane crossing — back to the position they crossed FROM, never to 'from'. */
    private void gateDeny(PlayerGateway p, LawCheckpointRecord site, PrevPos prev) {
        p.teleport(prev.dim(), prev.x(), prev.y(), prev.z());
        closeDoors(site);
        p.tellKey("straja.checkpoint.wrongway");
        playSound(p, DENY_SOUND);
        ledger(site, p, CrossingDirection.INCOMING, CrossingOutcome.DENY,
                ctx.deepScan().deepScan(p.uuid()), List.of(), "sens interzis la poartă");
        audit.record("checkpoint_deny", "checkpoint", "", p.name(), p.uuid().toString(),
                "DENY", "wrong-way " + site.id);
    }

    /** Right-way arrival at a linked gate: boarding stamp consumed, else full assessment. */
    private boolean rightWayArrival(PlayerGateway p, LawCheckpointRecord site, LawCheckpointStore store) {
        BoardingStamp stamp = boardStamps.get(p.uuid());
        long now = ctx.clock().nowMillis();
        if (stamp != null && stamp.expiresAt() > now && stamp.siteId().equals(site.linkedCheckpointId)) {
            boardStamps.remove(p.uuid());
            ledger(site, p, CrossingDirection.OUTGOING, CrossingOutcome.PASS,
                    ctx.deepScan().deepScan(p.uuid()), List.of(),
                    "controlat la îmbarcare (" + stamp.siteId() + ")");
            p.tellKey("straja.checkpoint.stamp_ok", site.linkedCheckpointId);
            return false;
        }
        return gateArrive(p, site, store);
    }

    /** Arrival without a stamp: banned pushed back, custody/hunted/contraband arrested. */
    private boolean gateArrive(PlayerGateway p, LawCheckpointRecord site, LawCheckpointStore store) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        if (isBanned(p, site, store)) {
            p.title("straja.checkpoint.deny_title", "straja.checkpoint.banned_sub");
            pushback(p, site);
            ledger(site, p, CrossingDirection.OUTGOING, CrossingOutcome.DENY,
                    snapshot, found, "interzis (ban)");
            return true;
        }
        if (inCustody(p)) {
            return arrest(p, site, "fugitiv prins la punctul de control",
                    snapshot, found, CrossingDirection.OUTGOING);
        }
        if (isHunted(p)) {
            return arrest(p, site, "vânat de Straja prins la frontieră",
                    snapshot, found, CrossingDirection.OUTGOING);
        }
        if (!found.isEmpty()) {
            return arrest(p, site, "marfă interzisă — control ocolit la îmbarcare",
                    snapshot, found, CrossingDirection.OUTGOING);
        }
        ledger(site, p, CrossingDirection.OUTGOING, CrossingOutcome.PASS,
                snapshot, found, "curat — trecere fără îmbarcare");
        return false;
    }

    /** Dock-side check for boat/raft riders. Returns true when it arrested the rider. */
    private boolean boardCheck(PlayerGateway p, LawCheckpointRecord site, LawCheckpointStore store) {
        BoardingStamp stamp = boardStamps.get(p.uuid());
        if (stamp != null && stamp.expiresAt() > ctx.clock().nowMillis()) return false;
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        boolean jailed = inCustody(p);
        boolean banned = isBanned(p, site, store);
        boolean hunted = isHunted(p);
        if (!found.isEmpty() || banned || jailed || hunted) {
            String reason = jailed ? "fugitiv la îmbarcare"
                    : banned ? "interzis la îmbarcare (ban activ)"
                    : hunted ? "vânat la îmbarcare"
                    : "marfă interzisă la îmbarcare";
            return arrest(p, site, reason, snapshot, found, CrossingDirection.INCOMING);
        }
        boardStamps.put(p.uuid(), new BoardingStamp(site.id, ctx.clock().nowMillis() + BOARD_STAMP_MS));
        p.tellKey("straja.checkpoint.board_ok", BOARD_STAMP_MS / 60000);
        ledger(site, p, CrossingDirection.INCOMING, CrossingOutcome.PASS,
                snapshot, found, "boarded curat");
        return false;
    }

    // ------------------------------------------------------------ decisions

    /** Exempt lists hold names or UUIDs (prototype parity: ops are scanned like anyone). */
    private boolean isExempt(PlayerGateway p, LawCheckpointRecord site, LawCheckpointStore store) {
        String name = p.name();
        String uuid = p.uuid().toString();
        return containsIgnoreCase(site.exemptions, name)
                || containsIgnoreCase(site.exemptions, uuid)
                || containsIgnoreCase(store.globalExemptions(), name)
                || containsIgnoreCase(store.globalExemptions(), uuid);
    }

    /** Ban lists hold names or UUIDs; legacy name-only bans still apply. */
    private boolean isBanned(PlayerGateway p, LawCheckpointRecord site, LawCheckpointStore store) {
        String name = p.name();
        String uuid = p.uuid().toString();
        return containsIgnoreCase(store.globalBans(), name)
                || containsIgnoreCase(store.globalBans(), uuid)
                || containsIgnoreCase(site.bannedPlayerUuids, name)
                || containsIgnoreCase(site.bannedPlayerUuids, uuid)
                || containsIgnoreCase(site.legacyBannedNames, name);
    }

    /** Physical custody: register in IN_CELL/IN_CAMP/ESCORTED or an active sentence. */
    private boolean inCustody(PlayerGateway p) {
        String uuid = p.uuid().toString();
        var rec = ctx.prisonerRegister().read().prisoner(uuid);
        if (rec != null && (rec.status == PrisonerStatus.IN_CELL
                || rec.status == PrisonerStatus.IN_CAMP
                || rec.status == PrisonerStatus.ESCORTED)) return true;
        return prison.activeSentence(p) != null;
    }

    /** Hunted: thief flag, register FUGITIVE, live BOLO, or unexpired legacy wanted mark. */
    private boolean isHunted(PlayerGateway p) {
        String uuid = p.uuid().toString();
        if (storage.isThief(p.uuid())) return true;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec != null && rec.status == PrisonerStatus.FUGITIVE) return true;
        Long until = reg.legacyWantedUntil().get(uuid);
        if (until != null && until > ctx.clock().nowMillis()) return true;
        for (var bolo : bolos.active()) {
            if (uuid.equals(bolo.subjectUuid)) return true;
        }
        return false;
    }

    /** Contraband = snapshot items whose id the site (or global list) marks illegal. */
    private List<String> contraband(List<SnapshotItem> snapshot,
                                    LawCheckpointRecord site, LawCheckpointStore store) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (SnapshotItem item : snapshot) {
            if (store.isIllegal(site, item.itemId)) {
                counts.merge(item.itemId, item.count, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>();
        counts.forEach((id, count) -> out.add(count + " x " + id));
        return out;
    }

    // ------------------------------------------------------------ arrest & deny effects

    /**
     * Arrest pipeline: the prison sentence is the custody source of truth —
     * when custody is unavailable ({@code prisonEnabled} off) the player is
     * repelled instead of booked, so the register never claims IN_CELL for
     * someone still walking free. The ledger snapshot was captured before
     * any mutation by the caller.
     */
    private boolean arrest(PlayerGateway p, LawCheckpointRecord site, String reason,
                           List<SnapshotItem> snapshot, List<String> found,
                           CrossingDirection dir) {
        var sentence = prison.arrest(p, null, 0, null, "checkpoint:" + site.id);
        if (sentence == null) {
            p.title("straja.checkpoint.deny_title", "straja.checkpoint.deny_sub");
            pushback(p, site);
            ledger(site, p, dir, CrossingOutcome.DENY, snapshot, found,
                    "arest indisponibil — prison oprit");
            return true;
        }
        String uuid = p.uuid().toString();
        long now = ctx.clock().nowMillis();
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec == null) {
            rec = new PrisonerRegisterRecord(uuid, p.name(), reason);
            reg.put(rec);
        }
        rec.status = PrisonerStatus.IN_CELL;
        rec.detentionReason = reason;
        rec.arrestSite = site.id;
        rec.bookedAt = now;
        rec.arrestCount++;
        if (rec.arrestSnapshot.isEmpty()) rec.arrestSnapshot.addAll(snapshot);
        if (rec.confiscatedSummary.isEmpty()) rec.confiscatedSummary.addAll(found);
        reg.legacyWantedUntil().remove(uuid);
        ctx.prisonerRegister().write(reg);

        // Custody consumes the hunt: thief flag + active BOLOs clear (AT6).
        storage.onArrested(p.uuid());
        bolos.clearFor(p.uuid());

        ledger(site, p, dir, CrossingOutcome.ARREST, snapshot, found, reason);
        audit.record("checkpoint_arrest", "checkpoint", "", p.name(), uuid, "ARREST", reason);
        p.title("straja.checkpoint.arrest_title", "straja.checkpoint.arrest_sub");
        playSound(p, DENY_SOUND);
        return true;
    }

    private void pushback(PlayerGateway p, LawCheckpointRecord site) {
        var target = site.pushback;
        if (target != null) {
            p.teleport(target.dimension(), target.x(), target.y(), target.z(), target.yaw(), 0f);
            p.setVelocity(target.vx(), target.vy(), target.vz());
        }
        closeDoors(site);
        playSound(p, DENY_SOUND);
    }

    private void closeDoors(LawCheckpointRecord site) {
        for (var door : site.doors) {
            ctx.world().closeDoor(door.dimension(), door.x(), door.y(), door.z());
        }
    }

    private void playSound(PlayerGateway p, String soundId) {
        ctx.world().playSoundAt(p.dimension(), p.x(), p.y(), p.z(), 24.0, soundId);
    }

    private void ledger(LawCheckpointRecord site, PlayerGateway p, CrossingDirection dir,
                        CrossingOutcome outcome, List<SnapshotItem> snapshot,
                        List<String> found, String detail) {
        var entry = new InspectionLedgerEntry(UUID.randomUUID().toString(),
                ctx.clock().nowMillis(), site.id, p.uuid().toString(),
                p.name(), dir, outcome);
        entry.detail = detail;
        entry.contrabandSummary.addAll(found);
        entry.inventorySnapshot.addAll(snapshot);
        var ledger = ctx.inspectionLedger().read();
        ledger.append(entry, ctx.policies().inspectionLedgerLimit);
        ctx.inspectionLedger().write(ledger);
    }

    // ------------------------------------------------------------ ledger query

    /** `/straja checkpoint ledger <player>` — newest entries first, name or uuid. */
    public void showLedger(PlayerGateway viewer, String playerRef) {
        String ref = playerRef == null ? "" : playerRef.trim();
        var ledger = ctx.inspectionLedger().read();
        List<InspectionLedgerEntry> matches = new ArrayList<>();
        for (var entry : ledger.entries()) {
            if (entry == null) continue;
            if (ref.equalsIgnoreCase(entry.playerName) || ref.equals(entry.playerUuid)) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            viewer.tellKey("straja.checkpoint.ledger_empty", ref);
            return;
        }
        viewer.tellKey("straja.checkpoint.ledger_header", ref, matches.size());
        int shown = 0;
        for (int i = matches.size() - 1; i >= 0 && shown < 10; i--, shown++) {
            var entry = matches.get(i);
            String line = pretty(entry.timestamp) + " — " + entry.checkpointId
                    + " — " + entry.direction + " — " + entry.outcome;
            if (!entry.contrabandSummary.isEmpty()) {
                line += " — " + String.join(", ", entry.contrabandSummary);
            }
            viewer.tell("§7" + line);
        }
    }

    private static String pretty(long timestamp) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.Instant.ofEpochMilli(timestamp)
                        .atZone(java.time.ZoneId.systemDefault()));
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        if (value == null) return false;
        for (String item : list) {
            if (value.equalsIgnoreCase(item)) return true;
        }
        return false;
    }
}
