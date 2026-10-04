package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.CrossingDirection;
import com.dwurdy.straja.domain.model.CrossingOutcome;
import com.dwurdy.straja.domain.model.GateLane;
import com.dwurdy.straja.domain.model.InspectionLedgerEntry;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.LawCheckpointStore;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.SnapshotItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final PersonnelService personnel;
    private final CustodyService custody; // nullable — escort bypass off when absent
    /** LAW-006: camp exit confiscation routes banned stacks to evidence. */
    private SeizureService seizure;
    /** LAW-007: the authoritative wanted surface once wired. */
    private WantedService wanted;

    private final Map<UUID, PrevPos> prevPositions = new ConcurrentHashMap<>();
    // Deliberate deviation: stamps are session-scoped, not persisted like the
    // prototype's cpBoard persistentData — a relog forces a fresh dock check
    // rather than trusting a 20-minute window across disconnects.
    private final Map<UUID, BoardingStamp> boardStamps = new ConcurrentHashMap<>();
    /** Armed admin picks: admin uuid → site+mode (evidence chests / doors). */
    private final Map<UUID, Pick> picks = new ConcurrentHashMap<>();

    private record PrevPos(String dim, double x, double y, double z) {}
    private record BoardingStamp(String siteId, long expiresAt) {}
    private record Pick(String siteId, String mode) {}

    public CheckpointService(StrajaContext ctx, AuditService audit,
                             PrisonService prison, StorageService storage, BoloService bolos,
                             PersonnelService personnel, CustodyService custody) {
        this.ctx = ctx;
        this.audit = audit;
        this.prison = prison;
        this.storage = storage;
        this.bolos = bolos;
        this.personnel = personnel;
        this.custody = custody;
    }

    /** LAW-006: late-bound (seizure is built after the checkpoint service). */
    public void useSeizure(SeizureService service) {
        this.seizure = service;
    }

    /** LAW-007: late-bound (wanted consolidates bolos+register after build). */
    public void useWanted(WantedService service) {
        this.wanted = service;
    }

    /** Officer escort (cuffs) only — #231: a bounty-bound captive is NOT an
     *  escorted prisoner; they are a wanted player being delivered, and the
     *  gate's wanted-on-sight arrest pipeline IS the delivery. */
    private boolean underEscort(PlayerGateway p, double radius) {
        return custody != null
                && custody.escortOfficerWithin(p, radius) != null;
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
        // Creative/spectator players are outside checkpoint jurisdiction entirely —
        // same gate the prototype applied to every pipeline (lanes, stages, boarding).
        String gm = p.gameModeName();
        if (!"survival".equals(gm) && !"adventure".equals(gm)) return;

        String dim = p.dimension();
        double x = p.x(), y = p.y(), z = p.z();
        PrevPos prev = prevPositions.get(p.uuid());

        // M4 escort bypass (AT8): a cuffed suspect beside their escorting
        // officer passes every checkpoint pipeline — the tether IS the
        // custody, so the gate does not repel what an officer escorts.
        // Camp exits are the one exception: the officer may escort the
        // prisoner out, but the camp's banned cargo does not leave with them.
        if (underEscort(p, ctx.policies().escortGateBypassRadius)) {
            confiscateEscortedAtCampExit(p, store, dim, x, y, z);
            prevPositions.put(p.uuid(), new PrevPos(dim, x, y, z));
            return;
        }

        // Role resolution reads several stores — do it once per player per scan.
        Set<String> roles = rolesOf(p);

        // Boarding is position-state, not a crossing: a rider inside the dock
        // zone is checked even on first sighting (prototype cpBoardScan had no
        // prev dependency). An arrest here mutates position — stop scanning.
        for (var site : store.checkpoints().values()) {
            if (site == null || !site.active || isExempt(p, site, store, roles)) continue;
            if (site.boardZone != null && p.ridingBoatLike()
                    && site.boardZone.contains(dim, x, y, z)
                    && boardCheck(p, site, store, roles)) {
                reseed(p);
                return;
            }
        }

        prevPositions.put(p.uuid(), new PrevPos(dim, x, y, z));
        if (prev == null || !prev.dim().equals(dim)) return;

        double mx = x - prev.x(), mz = z - prev.z();
        if (mx == 0 && mz == 0 && y == prev.y()) return;            // no movement
        boolean bigStep = mx * mx + mz * mz > MAX_STEP * MAX_STEP;  // teleport jump

        for (var site : store.checkpoints().values()) {
            if (site == null || !site.active || isExempt(p, site, store, roles)) continue;

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
                            && rightWayArrival(p, site, store, roles)) {
                        reseed(p);
                        return;
                    }
                    break;
                }
            }
            if (handleStages(p, site, store, roles, prev, x, y, z)) {
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
                                 LawCheckpointStore store, Set<String> roles,
                                 PrevPos prev, double x, double y, double z) {
        String dim = p.dimension();
        if (site.direction != CrossingDirection.BIDIRECTIONAL) {
            CrossingDirection dir = directionOf(site, prev, dim);
            if (dir != null && dir != site.direction) return false; // unpoliced direction
        }
        boolean entered1 = entered(site.stage1, dim, prev, x, y, z);
        boolean entered2 = entered(site.stage2, dim, prev, x, y, z);
        if (!entered1 && !entered2) return false;
        if (site.mode == com.dwurdy.straja.domain.model.CheckpointMode.DENY) {
            return denyStage(p, site, store, roles, dir(site, prev, dim));
        }
        return entered2 ? arrestStage2(p, site, store, roles, dir(site, prev, dim))
                        : inspect(p, site, store, roles, dir(site, prev, dim));
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

    /** Stage 1 — banned/role-banned push back, custody/hunted arrest on sight, contraband warns. */
    private boolean inspect(PlayerGateway p, LawCheckpointRecord site,
                            LawCheckpointStore store, Set<String> roles, CrossingDirection dir) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        List<String> carry = carryBanHits(snapshot, site, roles);
        found.addAll(carry);
        String roleBan = bannedRole(roles, site);
        // Custody/hunted first: a wanted or fugitive player is arrested on sight
        // even when they would also match a ban list — repelling them keeps them free.
        if (inCustody(p)) {
            arrest(p, site, "fugitiv prins la punctul de control", snapshot, found, dir);
            return true;
        }
        if (isHunted(p)) {
            // AT6: wanted on-sight at ANY arresting gate — clean pockets don't protect.
            arrest(p, site, "vânat de Straja prins la frontieră", snapshot, found, dir);
            return true;
        }
        if (isBanned(p, site, store) || roleBan != null) {
            p.title("straja.checkpoint.deny_title",
                    roleBan != null ? "straja.checkpoint.role_ban_sub" : "straja.checkpoint.banned_sub");
            pushback(p, site);
            ledger(site, p, dir, CrossingOutcome.DENY, snapshot, found,
                    roleBan != null ? "rol interzis: " + roleBan : "interzis (ban)");
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
        if (!carry.isEmpty()) p.tellKey("straja.checkpoint.quartermaster");
        return false;
    }

    /** Stage 2 — arrest assessment: custody > hunted > banned > contraband; clean logs 'out'. */
    private boolean arrestStage2(PlayerGateway p, LawCheckpointRecord site,
                                 LawCheckpointStore store, Set<String> roles, CrossingDirection dir) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        found.addAll(carryBanHits(snapshot, site, roles));
        boolean banned = isBanned(p, site, store);
        String roleBan = bannedRole(roles, site);
        boolean jailed = inCustody(p);
        boolean hunted = isHunted(p);
        if (!banned && roleBan == null && !jailed && !hunted && found.isEmpty()) {
            ledger(site, p, dir, CrossingOutcome.PASS, snapshot, found, "curat — a trecut frontiera");
            return false;
        }
        String reason = jailed ? "fugitiv prins la punctul de control"
                : hunted ? "vânat de Straja prins la frontieră"
                : banned ? "interdicție la punctul de control (ban activ)"
                : roleBan != null ? "rol interzis la frontieră: " + roleBan
                : "marfă interzisă la frontieră";
        return arrest(p, site, reason, snapshot, found, dir);
    }

    /** DENY-mode stage entry — violators repelled (wanted logged as sighting), clean pass. */
    private boolean denyStage(PlayerGateway p, LawCheckpointRecord site,
                              LawCheckpointStore store, Set<String> roles, CrossingDirection dir) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        List<String> carry = carryBanHits(snapshot, site, roles);
        found.addAll(carry);
        String roleBan = bannedRole(roles, site);
        String violation = null;
        if (isBanned(p, site, store)) violation = "interzis (ban)";
        else if (roleBan != null) violation = "rol interzis: " + roleBan;
        else if (inCustody(p)) violation = "deținut la poartă";
        else if (!found.isEmpty()) violation = carry.isEmpty() ? "marfă interzisă" : "restricție rol — marfă de vândut";
        else if (isHunted(p)) violation = "vânat reperat la poartă";
        if (violation == null) {
            ledger(site, p, dir, CrossingOutcome.PASS, snapshot, found, "curat");
            return false;
        }
        p.title("straja.checkpoint.deny_title",
                roleBan != null ? "straja.checkpoint.role_ban_sub" : "straja.checkpoint.deny_sub");
        pushback(p, site);
        if (!carry.isEmpty()) p.tellKey("straja.checkpoint.quartermaster");
        confiscateAtCampExit(p, site, bannedItemIds(snapshot, site, store, roles));
        ledger(site, p, dir, CrossingOutcome.DENY, snapshot, found, violation);
        return true;
    }

    /**
     * LAW-006: a camp prisoner repelled at their camp's exit gate loses the
     * banned commodities they tried to carry out — confiscated stacks route
     * into the site's evidence chain via the seizure engine.
     */
    /** Raw item ids the site bans for this carrier — global/site-illegal plus role carry bans. */
    private Set<String> bannedItemIds(List<SnapshotItem> snapshot, LawCheckpointRecord site,
                                      LawCheckpointStore store, Set<String> roles) {
        Set<String> ids = new HashSet<>();
        for (SnapshotItem item : snapshot) {
            if (item != null && item.itemId != null && store.isIllegal(site, item.itemId)) {
                ids.add(item.itemId);
            }
        }
        for (var e : site.roleCarryBans.entrySet()) {
            if (e.getValue() == null) continue;
            boolean hasRole = roles.stream().anyMatch(r -> r.equalsIgnoreCase(e.getKey()));
            if (!hasRole) continue;
            for (SnapshotItem item : snapshot) {
                if (item != null && item.itemId != null
                        && e.getValue().stream().anyMatch(id -> item.itemId.equalsIgnoreCase(id))) {
                    ids.add(item.itemId);
                }
            }
        }
        return ids;
    }

    private void confiscateAtCampExit(PlayerGateway p, LawCheckpointRecord site,
                                      Set<String> found) {
        if (seizure == null || site == null || found == null || found.isEmpty()) return;
        boolean campExit = false;
        for (var camp : ctx.laborCamps().read().camps().values()) {
            if (camp != null && site.id.equals(camp.exitCheckpointId)) {
                campExit = true;
                break;
            }
        }
        if (!campExit) return;
        String uuid = p.uuid() == null ? "" : p.uuid().toString();
        var rec = ctx.prisonerRegister().read().prisoner(uuid);
        if (rec == null || (rec.status != PrisonerStatus.IN_CAMP
                && rec.status != PrisonerStatus.ESCORTED)
                || rec.assignedCampId == null || rec.assignedCampId.isBlank()) {
            return;
        }
        int n = seizure.confiscateItems(p, site, found);
        if (n > 0) p.tellKey("straja.camp.confiscated", n);
    }

    /**
     * LAW-006 escort variant: an officer walking a cuffed prisoner out the
     * camp exit keeps custody, but the prisoner's banned cargo is still
     * confiscated into evidence — escort authority is not a smuggle channel.
     */
    private void confiscateEscortedAtCampExit(PlayerGateway p, LawCheckpointStore store,
                                              String dim, double x, double y, double z) {
        if (seizure == null) return;
        String uuid = p.uuid() == null ? "" : p.uuid().toString();
        var rec = ctx.prisonerRegister().read().prisoner(uuid);
        if (rec == null || (rec.status != PrisonerStatus.IN_CAMP
                && rec.status != PrisonerStatus.ESCORTED)
                || rec.assignedCampId == null || rec.assignedCampId.isBlank()) {
            return;
        }
        LawCheckpointRecord exit = null;
        for (var camp : ctx.laborCamps().read().camps().values()) {
            if (camp == null || !rec.assignedCampId.equals(camp.id)
                    || camp.exitCheckpointId == null) continue;
            var site = store.checkpoints().get(camp.exitCheckpointId);
            if (site != null && insideSite(site, dim, x, y, z)) {
                exit = site;
                break;
            }
        }
        if (exit == null) return;
        var snapshot = ctx.deepScan().deepScan(p.uuid());
        var ids = bannedItemIds(snapshot, exit, store, rolesOf(p));
        int n = seizure.confiscateItems(p, exit, ids);
        if (n > 0) p.tellKey("straja.camp.confiscated", n);
    }

    private static boolean insideSite(LawCheckpointRecord site, String dim,
                                      double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        return site.stage1 != null && site.stage1.contains(dim, bx, by, bz)
                || site.stage2 != null && site.stage2.contains(dim, bx, by, bz);
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
    private boolean rightWayArrival(PlayerGateway p, LawCheckpointRecord site,
                                    LawCheckpointStore store, Set<String> roles) {
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
        return gateArrive(p, site, store, roles);
    }

    /** Arrival without a stamp: banned/role-banned pushed back, custody/hunted/contraband arrested. */
    private boolean gateArrive(PlayerGateway p, LawCheckpointRecord site,
                               LawCheckpointStore store, Set<String> roles) {
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        found.addAll(carryBanHits(snapshot, site, roles));
        String roleBan = bannedRole(roles, site);
        // Custody/hunted before bans — arrest on sight beats repelling a fugitive.
        if (inCustody(p)) {
            return arrest(p, site, "fugitiv prins la punctul de control",
                    snapshot, found, CrossingDirection.OUTGOING);
        }
        if (isHunted(p)) {
            return arrest(p, site, "vânat de Straja prins la frontieră",
                    snapshot, found, CrossingDirection.OUTGOING);
        }
        if (isBanned(p, site, store) || roleBan != null) {
            p.title("straja.checkpoint.deny_title",
                    roleBan != null ? "straja.checkpoint.role_ban_sub" : "straja.checkpoint.banned_sub");
            pushback(p, site);
            ledger(site, p, CrossingDirection.OUTGOING, CrossingOutcome.DENY,
                    snapshot, found, roleBan != null ? "rol interzis: " + roleBan : "interzis (ban)");
            return true;
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
    private boolean boardCheck(PlayerGateway p, LawCheckpointRecord site,
                               LawCheckpointStore store, Set<String> roles) {
        BoardingStamp stamp = boardStamps.get(p.uuid());
        if (stamp != null && stamp.expiresAt() > ctx.clock().nowMillis()) return false;
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(p.uuid());
        List<String> found = contraband(snapshot, site, store);
        found.addAll(carryBanHits(snapshot, site, roles));
        boolean jailed = inCustody(p);
        boolean banned = isBanned(p, site, store);
        String roleBan = bannedRole(roles, site);
        boolean hunted = isHunted(p);
        if (!found.isEmpty() || banned || roleBan != null || jailed || hunted) {
            String reason = jailed ? "fugitiv la îmbarcare"
                    : banned ? "interzis la îmbarcare (ban activ)"
                    : roleBan != null ? "rol interzis la îmbarcare: " + roleBan
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

    /**
     * LAW-003: a player's gate roles — {@code PRISONER} when in custody, the
     * personnel grade/track/professions of an AUTHORIZED_ACTIVE member, else
     * {@code CIVIL}. All values compare case-insensitively against ban,
     * carry-ban and exemption lists.
     */
    private Set<String> rolesOf(PlayerGateway p) {
        Set<String> roles = new HashSet<>();
        if (inCustody(p)) roles.add("PRISONER");
        PersonnelRecord rec = personnel.find(p.uuid().toString());
        if (rec != null && rec.active()) {
            if (rec.careerGrade != null) roles.add(rec.careerGrade.name());
            if (rec.careerTrack != null) roles.add(rec.careerTrack.name());
            for (String prof : rec.professions) {
                if (prof != null && !prof.isBlank()) roles.add(prof.trim().toUpperCase(Locale.ROOT));
            }
        } else {
            roles.add("CIVIL");
        }
        return roles;
    }

    /** Role-ban hit: the first configured role the player actually has, else null. */
    private String bannedRole(Set<String> roles, LawCheckpointRecord site) {
        for (String banned : site.roleBans) {
            if (banned == null) continue;
            for (String role : roles) {
                if (banned.equalsIgnoreCase(role)) return banned;
            }
        }
        return null;
    }

    /**
     * Role carry-bans: items restricted only for matching roles, reported with
     * the role so the remedy ("sell to the quartermaster") stays attributable.
     * {@code localAllowedItems} does NOT lift a carry ban — role policy wins.
     */
    private List<String> carryBanHits(List<SnapshotItem> snapshot,
                                      LawCheckpointRecord site, Set<String> roles) {
        List<String> out = new ArrayList<>();
        for (var e : site.roleCarryBans.entrySet()) {
            String role = e.getKey();
            if (role == null || e.getValue() == null) continue;
            boolean hasRole = roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
            if (!hasRole) continue;
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (SnapshotItem item : snapshot) {
                if (item != null && item.itemId != null
                        && e.getValue().stream().anyMatch(id -> item.itemId.equalsIgnoreCase(id))) {
                    counts.merge(item.itemId, item.count, Integer::sum);
                }
            }
            counts.forEach((id, count) -> out.add(count + " x " + id + " (rol: " + role + ")"));
        }
        return out;
    }

    private static boolean containsAnyIgnoreCase(List<String> list, Set<String> values) {
        for (String v : values) {
            if (containsIgnoreCase(list, v)) return true;
        }
        return false;
    }

    /** Exempt lists hold names, UUIDs or role names (ops are scanned like anyone). */
    private boolean isExempt(PlayerGateway p, LawCheckpointRecord site,
                             LawCheckpointStore store, Set<String> roles) {
        String name = p.name();
        String uuid = p.uuid().toString();
        return containsIgnoreCase(site.exemptions, name)
                || containsIgnoreCase(site.exemptions, uuid)
                || containsIgnoreCase(store.globalExemptions(), name)
                || containsIgnoreCase(store.globalExemptions(), uuid)
                || containsAnyIgnoreCase(site.exemptions, roles)
                || containsAnyIgnoreCase(store.globalExemptions(), roles);
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

    /** Physical custody: register in IN_CELL/IN_CAMP/ESCORTED or an active
     * sentence — but a prisoner beside a live escorting officer is in lawful
     * custody right now, and the gate must not re-arrest them. */
    private boolean inCustody(PlayerGateway p) {
        if (underEscort(p, ctx.policies().escortTetherRadius)) {
            return false;
        }
        String uuid = p.uuid().toString();
        var rec = ctx.prisonerRegister().read().prisoner(uuid);
        if (rec != null && (rec.status == PrisonerStatus.IN_CELL
                || rec.status == PrisonerStatus.IN_CAMP
                || rec.status == PrisonerStatus.ESCORTED)) return true;
        return prison.activeSentence(p) != null;
    }

    /** Hunted: thief flag plus the authoritative wanted surface (LAW-007). */
    private boolean isHunted(PlayerGateway p) {
        if (storage.isThief(p.uuid())) return true;
        if (wanted != null) return wanted.isWanted(p.uuid());
        String uuid = p.uuid().toString();
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
        var sentence = prison.arrest(p, null, 0, null, "checkpoint:" + site.id,
                reason, site.id);
        if (sentence == null) {
            p.title("straja.checkpoint.deny_title", "straja.checkpoint.deny_sub");
            pushback(p, site);
            ledger(site, p, dir, CrossingOutcome.DENY, snapshot, found,
                    "arest indisponibil — prison oprit");
            return true;
        }
        // PrisonService owns booking (register, seizure, report); the
        // checkpoint only attaches the tamper-evident ledger snapshot it
        // captured before the arrest mutated the inventory (AT4).
        String uuid = p.uuid().toString();
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec != null) {
            if (rec.arrestSnapshot.isEmpty()) rec.arrestSnapshot.addAll(snapshot);
            if (rec.confiscatedSummary.isEmpty()) rec.confiscatedSummary.addAll(found);
            ctx.prisonerRegister().write(reg);
        }

        // Custody consumes the hunt: thief flag + active BOLOs resolve (AT6).
        storage.onArrested(p.uuid());
        bolos.resolveFor(p.uuid(), com.dwurdy.straja.domain.model.BoloStatus.RESOLVED,
                "arrested");

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

    // ------------------------------------------------------------ site picking (LAW-004)

    /** `pick <site> evidence|door|off` — arms a site-scoped pick for admin clicks. */
    public boolean setPickMode(PlayerGateway admin, String siteId, String mode) {
        if ("off".equals(mode) || siteId == null || mode == null) {
            picks.remove(admin.uuid());
            return "off".equals(mode);
        }
        var store = ctx.lawCheckpoints().read();
        var site = store.checkpoint(siteId);
        if (site == null) {
            admin.refuse("straja.checkpoint.no_site", "straja.remedy.fix_retry", siteId);
            return true;
        }
        if (!"evidence".equals(mode) && !"door".equals(mode)) return false;
        picks.put(admin.uuid(), new Pick(site.id, mode));
        return true;
    }

    /** Consumes an armed site pick; returns true when the click was used. */
    public boolean onPickClick(PlayerGateway admin, String dimension, int x, int y, int z) {
        var pick = picks.get(admin.uuid());
        if (pick == null) return false;
        var store = ctx.lawCheckpoints().read();
        var site = store.checkpoint(pick.siteId());
        if (site == null) {
            picks.remove(admin.uuid());
            admin.refuse("straja.checkpoint.no_site", "straja.remedy.fix_retry", pick.siteId());
            return true;
        }
        var point = new com.dwurdy.straja.domain.model.StoragePoint(dimension, x, y, z);
        if ("evidence".equals(pick.mode())) {
            if (!ctx.containers().isContainer(dimension, x, y, z)) {
                admin.refuse("straja.storage.pick_not_container", "straja.remedy.fix_retry");
                return true;
            }
            String canon = ctx.containers().canonicalKey(dimension, x, y, z);
            boolean dup = site.evidenceChests.stream().anyMatch(pt -> pt != null
                    && ctx.containers().canonicalKey(pt.dimension(), pt.x(), pt.y(), pt.z())
                            .equals(canon));
            boolean locker = ctx.prison().read().lockerPool.stream().anyMatch(pt -> pt != null
                    && ctx.containers().canonicalKey(pt.dimension(), pt.x(), pt.y(), pt.z())
                            .equals(canon));
            if (dup) {
                admin.tellKey("straja.storage.pick_chest_dup", canon);
            } else if (locker) {
                // Evidence and locker pools must not overlap — an evidence
                // chest counted as a locker would hand contraband back on release.
                admin.refuse("straja.checkpoint.pick_in_locker_pool",
                        "straja.remedy.fix_retry");
            } else {
                site.evidenceChests.add(point);
                store.put(site);
                ctx.lawCheckpoints().write(store);
                admin.tellKey("straja.checkpoint.pick_evidence", canon, site.id,
                        site.evidenceChests.size());
            }
            return true;
        }
        site.doors.add(point);
        store.put(site);
        ctx.lawCheckpoints().write(store);
        admin.tellKey("straja.checkpoint.pick_door", point.key(), site.id, site.doors.size());
        return true;
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

    // ------------------------------------------------------------ admin policy (LAW-003)

    /** `illegal add|remove <item> [site]` — blank/"global" site edits the global list. */
    public void setIllegalItem(PlayerGateway admin, String itemId, String siteId, boolean add) {
        var store = ctx.lawCheckpoints().read();
        String scope;
        if (siteId == null || siteId.isBlank() || "global".equalsIgnoreCase(siteId)) {
            if (add) store.globalIllegalItems().put(itemId, true);
            else store.globalIllegalItems().remove(itemId);
            scope = "global";
        } else {
            var site = store.checkpoint(siteId);
            if (site == null) {
                admin.tellKey("straja.checkpoint.no_site", siteId);
                return;
            }
            List<LawCheckpointRecord> targets = linkedScope(store, site);
            for (var s : targets) {
                if (add) {
                    if (!s.localIllegalItems.contains(itemId)) s.localIllegalItems.add(itemId);
                    s.localAllowedItems.remove(itemId); // illegal wins over allowed
                } else {
                    s.localIllegalItems.remove(itemId);
                }
            }
            scope = scopeName(targets);
        }
        ctx.lawCheckpoints().write(store);
        policy(admin, scope, (add ? "+illegal " : "-illegal ") + itemId);
    }

    /** `roleban add|remove <site> <role>` — repelled on sight, pockets irrelevant. */
    public void setRoleBan(PlayerGateway admin, String siteId, String role, boolean add) {
        var store = ctx.lawCheckpoints().read();
        var site = store.checkpoint(siteId);
        if (site == null) {
            admin.tellKey("straja.checkpoint.no_site", siteId);
            return;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        List<LawCheckpointRecord> targets = linkedScope(store, site);
        for (var s : targets) {
            if (add) {
                if (s.roleBans.stream().noneMatch(normalized::equalsIgnoreCase)) s.roleBans.add(normalized);
            } else {
                s.roleBans.removeIf(normalized::equalsIgnoreCase);
            }
        }
        ctx.lawCheckpoints().write(store);
        policy(admin, scopeName(targets), (add ? "+roleban " : "-roleban ") + normalized);
    }

    /** `carryban add|remove <site> <role> <item>` — role-specific item restriction. */
    public void setCarryBan(PlayerGateway admin, String siteId, String role, String itemId, boolean add) {
        var store = ctx.lawCheckpoints().read();
        var site = store.checkpoint(siteId);
        if (site == null) {
            admin.tellKey("straja.checkpoint.no_site", siteId);
            return;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        List<LawCheckpointRecord> targets = linkedScope(store, site);
        for (var s : targets) {
            var list = s.roleCarryBans.computeIfAbsent(normalized, k -> new ArrayList<>());
            if (add) {
                if (list.stream().noneMatch(itemId::equalsIgnoreCase)) list.add(itemId);
            } else {
                list.removeIf(itemId::equalsIgnoreCase);
                if (list.isEmpty()) s.roleCarryBans.remove(normalized);
            }
        }
        ctx.lawCheckpoints().write(store);
        policy(admin, scopeName(targets), (add ? "+carryban " : "-carryban ") + normalized + " " + itemId);
    }

    /**
     * `arrestdest <site> <CAMP:<id>|CELL>` — where this site's arrests
     * deliver the prisoner. Camp ids resolve against the labor-camp store;
     * unknown ids refuse rather than silently degrading to cells.
     */
    public void setArrestDestination(PlayerGateway admin, String siteId, String destination) {
        var store = ctx.lawCheckpoints().read();
        var site = store.checkpoint(siteId);
        if (site == null) {
            admin.tellKey("straja.checkpoint.no_site", siteId);
            return;
        }
        String dest = destination == null ? "" : destination.trim();
        if (dest.isEmpty() || "CELL".equalsIgnoreCase(dest) || "CELLS".equalsIgnoreCase(dest)) {
            site.arrestDestination = "";
        } else if (dest.toUpperCase(Locale.ROOT).startsWith("CAMP:")) {
            String campId = dest.substring(5).trim();
            if (ctx.laborCamps().read().camp(campId) == null) {
                admin.refuse("straja.camp.unknown", "straja.remedy.fix_retry", campId);
                return;
            }
            site.arrestDestination = "CAMP:" + campId;
        } else {
            admin.refuse("straja.camp.bad_dest", "straja.remedy.fix_retry", destination);
            return;
        }
        ctx.lawCheckpoints().write(store);
        policy(admin, site.id, "arrestdest "
                + (site.arrestDestination.isEmpty() ? "CELL" : site.arrestDestination));
    }

    /** `exempt add|remove <site|global> <player|role>` — subject matched against name, uuid or role. */
    public void setExemption(PlayerGateway admin, String scope, String subject, boolean add) {
        var store = ctx.lawCheckpoints().read();
        List<String> list;
        String scopeName;
        if ("global".equalsIgnoreCase(scope)) {
            list = store.globalExemptions();
            scopeName = "global";
        } else {
            var site = store.checkpoint(scope);
            if (site == null) {
                admin.tellKey("straja.checkpoint.no_site", scope);
                return;
            }
            List<LawCheckpointRecord> targets = linkedScope(store, site);
            for (var s : targets) {
                if (add) {
                    if (s.exemptions.stream().noneMatch(subject::equalsIgnoreCase)) s.exemptions.add(subject);
                } else {
                    s.exemptions.removeIf(subject::equalsIgnoreCase);
                }
            }
            ctx.lawCheckpoints().write(store);
            policy(admin, scopeName(targets), (add ? "+exempt " : "-exempt ") + subject);
            return;
        }
        if (add) {
            if (list.stream().noneMatch(subject::equalsIgnoreCase)) list.add(subject);
        } else {
            list.removeIf(subject::equalsIgnoreCase);
        }
        ctx.lawCheckpoints().write(store);
        policy(admin, scopeName, (add ? "+exempt " : "-exempt ") + subject);
    }

    /** Linked-gate policy sharing: the site plus its link partner(s), both directions. */
    private List<LawCheckpointRecord> linkedScope(LawCheckpointStore store, LawCheckpointRecord site) {
        List<LawCheckpointRecord> scope = new ArrayList<>();
        scope.add(site);
        if (site.linkedCheckpointId != null && !site.linkedCheckpointId.isBlank()) {
            var other = store.checkpoint(site.linkedCheckpointId);
            if (other != null && !other.id.equals(site.id)) scope.add(other);
        }
        for (var cand : store.checkpoints().values()) {
            if (cand != null && site.id.equals(cand.linkedCheckpointId)
                    && scope.stream().noneMatch(s -> s.id.equals(cand.id))) {
                scope.add(cand);
            }
        }
        return scope;
    }

    private static String scopeName(List<LawCheckpointRecord> targets) {
        return targets.size() == 1 ? targets.get(0).id
                : targets.get(0).id + "+" + (targets.size() - 1) + " linked";
    }

    private void policy(PlayerGateway admin, String scope, String what) {
        audit.record("checkpoint_policy", "checkpoint", "", admin.name(),
                admin.uuid().toString(), "POLICY", scope + " " + what);
        admin.tellKey("straja.checkpoint.policy_set", scope, what);
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
