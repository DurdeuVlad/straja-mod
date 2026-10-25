package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.application.port.in.PrisonRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.Cell;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.PrisonStore;
import com.dwurdy.straja.domain.model.Sentence;
import com.dwurdy.straja.domain.model.SetupData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;

/**
 * Prison: bounded cells, sentences with online-active-only time, waitlist,
 * release points and cell protection. Ported from the reference civic service.
 */
public class PrisonService implements PrisonRoleplayUseCase {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final CustodyRoleplayUseCase custody;
    private final java.util.List<Consumer<Sentence>> arrestHooks = new java.util.ArrayList<>();
    private final java.util.List<BiConsumer<Sentence, String>> sentenceCompletedHooks = new java.util.ArrayList<>();
    private SeizureService seizure;
    private BoloService bolos;
    /** LAW-006 camp custody queries (late-bound like useSeizure). */
    private LaborCampService camps;
    /** DEBT-1 pending book notices (late-bound like useSeizure). */
    private DebtService debt;
    /** FUN-7: a completed break opens manhunts for every participant. */
    private ManhuntService manhunts;
    private long lastTickMs;
    /** Armed admin picks for the locker pool (admin uuid → mode). */
    private final java.util.Map<java.util.UUID, String> pickModes = new java.util.HashMap<>();
    /** FUN-7: in-flight tamper channels keyed by cell id — memory-only,
     *  a restart mid-channel simply cancels the attempt. */
    private final java.util.Map<String, Tamper> tamperAttempts = new java.util.HashMap<>();

    private record Tamper(String cellId, String breakerUuid, String breakerName,
                          long completesAt, double x, double y, double z,
                          String tool) {}

    /** FUN-7: admin lock-tier — WEAK falls to the crowbar, STANDARD needs cutters. */
    public boolean setCellLockTier(PlayerGateway admin, String cellId, String tier) {
        var data = store();
        var cell = data.cell(cellId);
        if (cell == null) {
            if (admin != null) admin.refuse("straja.cell.unknown", "straja.remedy.fix_retry");
            return false;
        }
        String t = tier == null ? "" : tier.toUpperCase(java.util.Locale.ROOT);
        if (!t.equals("STANDARD") && !t.equals("WEAK")) {
            if (admin != null) admin.refuse("straja.cell.tamper_tier", "straja.remedy.fix_retry");
            return false;
        }
        cell.lockTier = t;
        ctx.prison().write(data);
        audit.record(AuditActions.PRISON_CELL_CREATE, admin == null ? "" : admin.name(),
                admin == null ? "" : uuidOf(admin), cell.id, "", AuditResults.SUCCESS,
                "locktier=" + t);
        if (admin != null) admin.tell("Celula " + cell.id + " are broască " + t + ".");
        return true;
    }

    public PrisonService(StrajaContext ctx, PlayerService players, AuditService audit,
                         CustodyRoleplayUseCase custody) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.custody = custody;
    }

    /** M4: the arrest-time seizure engine (late-bound — storage/checkpoints wire after). */
    public void useSeizure(SeizureService service) {
        this.seizure = service;
    }

    /** M4: fugitive marking writes system BOLOs (late-bound like useSeizure). */
    public void useBolos(BoloService service) {
        this.bolos = service;
    }

    /** LAW-006: labor-camp destinations, bounds, spawns and freedom prices. */
    public void useCamps(LaborCampService service) {
        this.camps = service;
    }

    /** DEBT-1: queued written-book notices drain at login (late-bound like useSeizure). */
    public void useDebt(DebtService service) {
        this.debt = service;
    }

    /** FUN-7: completed breaks open manhunts (late-bound like useSeizure). */
    public void useManhunts(ManhuntService service) {
        this.manhunts = service;
    }

    /** FUN-8: ordered transfers become escorted rides (late-bound). */
    private TransferService transfers;
    public void useTransfers(TransferService service) {
        this.transfers = service;
    }

    /** FUN-9: the blend path can suppress a bounds breach into a window. */
    private EscapeService escapes;
    public void useEscapes(EscapeService service) {
        this.escapes = service;
    }

    /** FUN-9: pick jam rolls stay unit-testable (#247 seam). */
    private com.dwurdy.straja.application.port.out.RollSource rolls =
            com.dwurdy.straja.application.port.out.RollSource.system();
    public void useRolls(com.dwurdy.straja.application.port.out.RollSource source) {
        this.rolls = source == null
                ? com.dwurdy.straja.application.port.out.RollSource.system() : source;
    }

    public void onArrest(Consumer<Sentence> hook) {
        if (hook != null) arrestHooks.add(hook);
    }

    public void onSentenceCompleted(BiConsumer<Sentence, String> hook) {
        if (hook != null) sentenceCompletedHooks.add(hook);
    }

    private long now() { return ctx.clock().nowMillis(); }
    private PrisonStore store() { return ctx.prison().read(); }

    private static String key(PlayerGateway p) {
        String uuid = p.uuid() == null ? "" : p.uuid().toString();
        return !uuid.isEmpty() ? uuid : PlayerService.canon(p.name());
    }

    private PlayerGateway findFor(Sentence s) {
        for (var p : ctx.server().onlinePlayers()) {
            if (PlayerService.identityMatches(p, s.targetUuid, s.target)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------ cells

    public boolean createCell(PlayerGateway actor, String requestedId,
                              String dimension, int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ) {
        if (!players.isCommissioner(actor)) {
            actor.refuse("straja.prison.cells_comisar", "straja.remedy.ask_comisar");
            return false;
        }
        var data = store();
        String id = (requestedId == null || requestedId.isBlank()
                ? nextCellId(data) : requestedId).toLowerCase();
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            actor.refuse("straja.prison.cell_invalid", "straja.remedy.fix_retry");
            return false;
        }
        if (data.assignments.containsKey(id)) {
            actor.refuse("straja.prison.cell_occupied", "straja.remedy.wait");
            return false;
        }
        if (data.cells.size() >= ctx.policies().prisonMaxCells && data.cell(id) == null) {
            actor.refuse("straja.prison.max_cells", "straja.remedy.ask_comisar");
            return false;
        }
        var cell = new Cell();
        cell.id = id;
        cell.dimension = dimension;
        cell.minX = Math.min(minX, maxX);
        cell.minY = Math.min(minY, maxY);
        cell.minZ = Math.min(minZ, maxZ);
        cell.maxX = Math.max(minX, maxX);
        cell.maxY = Math.max(minY, maxY);
        cell.maxZ = Math.max(minZ, maxZ);
        var geometryErrorKey = validateCellGeometry(cell);
        if (geometryErrorKey != null) {
            actor.refuse(geometryErrorKey, "straja.remedy.fix_retry");
            audit.record(AuditActions.PRISON_CELL_CREATE, actor.name(), uuidOf(actor), id, "", AuditResults.REFUSED, "invalid_geometry");
            return false;
        }
        cell.createdAt = now();
        data.cells.removeIf(c -> c.id.equalsIgnoreCase(id));
        data.cells.add(cell);
        ctx.prison().write(data);
        actor.tell("Celula " + id + " a fost creată.");
        audit.record(AuditActions.PRISON_CELL_CREATE, actor.name(), uuidOf(actor),
                "", "", AuditResults.SUCCESS, "cellId=" + id);
        processWaitlist();
        return true;
    }

    /** Validates the same bounded shell and single vertical door contract as rooms. */
    private String validateCellGeometry(Cell cell) {
        int interiorX = cell.maxX - cell.minX + 1;
        int interiorY = cell.maxY - cell.minY + 1;
        int interiorZ = cell.maxZ - cell.minZ + 1;
        if (interiorX < ctx.policies().roomMinInteriorX
                || interiorY < ctx.policies().roomMinInteriorY
                || interiorZ < ctx.policies().roomMinInteriorZ) {
            return "straja.prison.cell_too_small";
        }
        int minX = cell.minX - 1, minY = cell.minY - 1, minZ = cell.minZ - 1;
        int maxX = cell.maxX + 1, maxY = cell.maxY + 1, maxZ = cell.maxZ + 1;
        long volume = (long) maxX - minX + 1;
        volume *= (long) maxY - minY + 1;
        volume *= (long) maxZ - minZ + 1;
        if (maxX - minX + 1 > ctx.policies().roomMaxDimension
                || maxY - minY + 1 > ctx.policies().roomMaxDimension
                || maxZ - minZ + 1 > ctx.policies().roomMaxDimension
                || volume > ctx.policies().roomMaxBlocks) {
            return "straja.prison.cell_too_large";
        }
        List<int[]> doors = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (!boundary) continue;
                    var block = ctx.world().blockAt(cell.dimension, x, y, z);
                    if (block == null) return "straja.prison.cell_unreadable";
                    if (block.solid()) continue;
                    if (block.door()) {
                        doors.add(new int[]{x, y, z});
                        continue;
                    }
                    return "straja.prison.cell_open_wall";
                }
            }
        }
        if (ctx.policies().roomRequireSingleDoor) {
            if (doors.size() != 2 || doors.get(0)[0] != doors.get(1)[0]
                    || doors.get(0)[2] != doors.get(1)[2]
                    || Math.abs(doors.get(0)[1] - doors.get(1)[1]) != 1) {
                return "straja.prison.cell_door";
            }
        } else if (doors.isEmpty()) {
            return "straja.prison.cell_door_missing";
        }
        int[] door = doors.stream().min(Comparator.comparingInt(d -> d[1])).orElse(null);
        if (door != null) {
            cell.doorX = door[0]; cell.doorY = door[1]; cell.doorZ = door[2];
        }
        return null;
    }

    public void listCells(PlayerGateway actor) {
        var data = store();
        if (data.cells.isEmpty()) {
            actor.refuse("straja.prison.no_cells", "straja.remedy.ask_comisar");
            return;
        }
        for (Cell c : data.cells) {
            var assignment = data.assignments.get(c.id);
            actor.tell(c.id + " [" + c.dimension + "] (" + c.minX + "," + c.minY + "," + c.minZ
                    + "→" + c.maxX + "," + c.maxY + "," + c.maxZ + ")"
                    + (assignment == null ? " liberă" : " ocupată: " + assignment.target));
        }
    }

    private String nextCellId(PrisonStore data) {
        int number = 1;
        boolean taken = true;
        while (taken) {
            String candidate = "celula_" + number;
            taken = data.cells.stream().anyMatch(c -> c.id.equalsIgnoreCase(candidate));
            if (taken) number++;
        }
        return "celula_" + number;
    }

    private Cell firstFree(PrisonStore data) {
        return data.cells.stream()
                .filter(c -> c != null && c.id != null && !c.id.isBlank()
                        && !data.assignments.containsKey(c.id))
                .sorted((a, b) -> a.id.compareTo(b.id))
                .findFirst().orElse(null);
    }

    private void assignCell(PrisonStore data, Sentence sentence, Cell cell) {
        var assignment = new PrisonStore.Assignment();
        assignment.target = sentence.target;
        assignment.targetUuid = sentence.targetUuid;
        assignment.sentenceId = sentence.id;
        assignment.assignedAt = now();
        data.assignments.put(cell.id, assignment);
        sentence.cellId = cell.id;
        sentence.status = "ACTIVE";
        sentence.lastTickAt = now();
        sentence.lastActivityAt = now();
    }

    public void processWaitlist() {
        var data = store();
        boolean changed = false;
        for (var entry : new java.util.ArrayList<>(data.waitlist)) {
            if (entry == null || entry.sentenceId == null || entry.sentenceId.isBlank()) continue;
            Sentence found = null;
            for (Sentence s : data.sentences) {
                if (s != null && entry.sentenceId.equals(s.id)) found = s;
            }
            final Sentence sentence = found;
            if (sentence == null || !"WAITING_CELL".equals(sentence.status)) continue;
            // LAW-006: a camp-assigned prisoner holds no cell bunk — heal the
            // stale waitlist state rather than dragging them into jail.
            var rr = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
            if (rr != null && rr.status == PrisonerStatus.IN_CAMP
                    && rr.assignedCampId != null && !rr.assignedCampId.isBlank()) {
                sentence.status = "ACTIVE";
                data.waitlist.removeIf(e -> e != null
                        && entry.sentenceId.equals(e.sentenceId));
                changed = true;
                continue;
            }
            Cell cell = firstFree(data);
            if (cell == null) continue;
            assignCell(data, sentence, cell);
            data.waitlist.removeIf(e -> e != null && entry.sentenceId.equals(e.sentenceId));
            changed = true;
            var target = findFor(sentence);
            if (target != null && custody.enterJail(target, "prison")) {
                teleportToCell(target, cell);
                target.setGameMode("adventure");
                target.tell("Ai fost repartizat într-o celulă pentru executarea sentinței.");
            }
        }
        if (changed) ctx.prison().write(data);
    }

    // ------------------------------------------------------------ arrest/release

    public Sentence activeSentence(PlayerGateway target) {
        return activeSentenceFrom(store(), target);
    }

    /** {@inheritDoc} — the register's view of the player's detention state. */
    @Override
    public PrisonerStatus registerStatus(PlayerGateway player) {
        if (player == null || player.uuid() == null) return null;
        var record = ctx.prisonerRegister().read().prisoner(player.uuid().toString());
        return record == null ? null : record.status;
    }

    public Sentence findSentence(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Sentence s : store().sentences) if (s.id.equals(id)) return s;
        return null;
    }

    /** Finds the active sentence inside the given store graph (same objects that get written). */
    private Sentence activeSentenceFrom(PrisonStore data, PlayerGateway target) {
        String uuid = target.uuid() == null ? "" : target.uuid().toString();
        return uuid.isEmpty() ? data.activeSentenceFor(target.name()) : data.activeSentenceFor(uuid);
    }

    public Sentence arrest(PlayerGateway target, String fineId, int days,
                           PlayerGateway actor, String missionId) {
        return arrest(target, fineId, days, actor, missionId, "", "");
    }

    /**
     * {@code reason}/{@code siteId} carry the register context a checkpoint
     * arrest knows (detention reason, arrest site); officer-initiated arrests
     * use the 5-arg overload and leave both blank.
     */
    public Sentence arrest(PlayerGateway target, String fineId, int days,
                           PlayerGateway actor, String missionId,
                           String reason, String siteId) {
        if (!ctx.policies().prisonEnabled) {
            if (actor != null) actor.refuse("straja.prison.disabled", "straja.remedy.ask_comisar");
            audit.record(AuditActions.PRISON_ARREST, actor == null ? "" : actor.name(),
                    actor == null ? "" : uuidOf(actor), target.name(), uuidOf(target),
                    AuditResults.REFUSED, "prison_disabled");
            return null;
        }
        var data = store();
        var existing = activeSentence(target);
        if (existing != null) {
            recapture(target, existing);
            return existing;
        }
        // FUN-7: a non-prisoner runner (the springer) is captured by any
        // new sentence — pull them out of the hunt either way.
        if (manhunts != null && manhunts.huntFor(uuidOf(target)) != null) {
            manhunts.runnerCaptured(uuidOf(target));
        }
        // LAW-006: the arrest site's destination decides where the prisoner
        // lands — "CAMP:<id>" delivers to a labor camp, anything else is the
        // normal cell/waitlist path.
        var site = siteFor(siteId, missionId);
        var camp = campDestination(site);
        int max = Math.max(1, ctx.policies().prisonMaxSentenceDays);
        int fallback = Math.max(1, ctx.policies().prisonDefaultSentenceDays);
        int sentenceDays = days > 0 ? Math.min(max, days) : fallback;
        var sentence = new Sentence();
        sentence.id = "S" + now() + "-" + (data.sentences.size() + 1);
        sentence.target = target.name();
        sentence.targetUuid = uuidOf(target);
        sentence.fineId = fineId == null ? "" : fineId;
        sentence.missionId = missionId == null ? "" : missionId;
        sentence.arrestedBy = actor == null ? "" : actor.name();
        sentence.arrestedByUuid = actor == null ? "" : uuidOf(actor);
        sentence.sentenceDays = sentenceDays;
        sentence.remainingActiveMs = (long) sentenceDays
                * Math.max(1, ctx.policies().prisonActiveMinutesPerMinecraftDay) * 60_000L;
        sentence.status = "WAITING_CELL";
        sentence.createdAt = now();
        sentence.lastTickAt = now();
        sentence.lastActivityAt = now();
        data.sentences.add(sentence);
        var cell = firstFree(data);
        if (camp != null) {
            // Camp sentences are ACTIVE without a bunk — camp bounds replace
            // the cell box and camp prisoners never sit on the waitlist.
            sentence.status = "ACTIVE";
        } else if (cell != null) {
            assignCell(data, sentence, cell);
        } else {
            var entry = new PrisonStore.WaitlistEntry();
            entry.sentenceId = sentence.id;
            entry.target = sentence.target;
            entry.targetUuid = sentence.targetUuid;
            entry.requestedAt = now();
            data.waitlist.add(entry);
        }
        ctx.prison().write(data);
        for (var hook : arrestHooks) hook.accept(sentence);
        var online = findFor(sentence);
        // Custody must land before inventory is touched: if the jail
        // transition is refused (foreign provider state), the target keeps
        // their belongings and the officer gets the handover retry prompt.
        // Waitlisted prisoners (no cell yet) are still seized — they are
        // booked even though the body waits for a bunk.
        boolean jailed = false;
        if (online != null && (!sentence.cellId.isEmpty() || camp != null)) {
            if (custody.enterJail(online, "prison")) {
                jailed = true;
            } else {
                online.refuse("straja.prison.handover_retry", "straja.remedy.jailer");
            }
        }
        if (online != null && seizure != null
                && (jailed || (camp == null && sentence.cellId.isEmpty()))) {
            // Custody consumes the person AND the inventory: seize at the
            // arrest position before the handover moves the body into a cell.
            // Waitlisted prisoners (no cell yet) are still seized — they are
            // booked even though the body waits for a bunk. A REFUSED camp
            // handover is neither: the suspect keeps their belongings and the
            // wanted marks until a successful recapture delivers them.
            seizure.onArrest(online, site, blank(reason) ? missionId : reason);
        }
        if (camp != null) {
            // Camp intake: the register claims IN_CAMP, the body lands at the
            // intake spawn, and mining requires survival — no adventure mode.
            // The camp link is written even when the custody handover was
            // refused, so a later arrest/recapture re-delivers to the camp
            // instead of stranding the sentence with no destination.
            var reg = ctx.prisonerRegister().read();
            var rec = reg.prisoner(sentence.targetUuid);
            if (rec == null) {
                rec = new com.dwurdy.straja.domain.model.PrisonerRegisterRecord(
                        sentence.targetUuid, sentence.target,
                        blank(reason) ? missionId : reason);
                reg.put(rec);
            }
            rec.assignedCampId = camp.id;
            if (site != null) rec.arrestSite = site.id;
            if (jailed) rec.status = PrisonerStatus.IN_CAMP;
            ctx.prisonerRegister().write(reg);
            if (jailed) deliverToCamp(online, camp, true);
        } else if (jailed) {
            teleportToCell(online, cell);
            online.setGameMode("adventure");
        }
        // LAW-007: the moment custody consumes the suspect — jailed or
        // waitlisted-seized — every wanted mark resolves on the books. A
        // refused camp handover does not count: still at large, still wanted.
        if (bolos != null && online != null && online.uuid() != null
                && (jailed || (camp == null && sentence.cellId.isEmpty()))) {
            bolos.resolveFor(online.uuid(),
                    com.dwurdy.straja.domain.model.BoloStatus.RESOLVED, "arrested");
        }
        if (online != null) {
            online.tell("Ai fost arestat pentru " + sentence.sentenceDays
                    + " zi(e) de Minecraft. Timpul se consumă doar când ești online și activ.");
        }
        audit.record(AuditActions.PRISON_ARREST, actor == null ? "" : actor.name(),
                actor == null ? "" : uuidOf(actor), sentence.target, sentence.targetUuid,
                AuditResults.SUCCESS, "sentenceId=" + sentence.id + " days=" + sentenceDays);
        return sentence;
    }

    /**
     * Re-arrest of an already-sentenced player — the recapture path for
     * fugitives and escorted prisoners returned to a cell. Restores the
     * register to IN_CELL, clears the system BOLO, and puts the body back.
     */
    private void recapture(PlayerGateway target, Sentence sentence) {
        String uuid = uuidOf(target);
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        boolean wasHunted = rec != null && (rec.status == PrisonerStatus.FUGITIVE
                || rec.status == PrisonerStatus.ESCORTED);
        // LAW-006: a camp prisoner stays camp-bound through recapture — the
        // intake spawn is their delivery point, not a cell bunk.
        var camp = rec != null && rec.assignedCampId != null
                && !rec.assignedCampId.isBlank() && camps != null
                ? camps.camp(rec.assignedCampId) : null;
        if (rec != null && !rec.status.isReleased()) {
            rec.status = camp != null ? PrisonerStatus.IN_CAMP : PrisonerStatus.IN_CELL;
            ctx.prisonerRegister().write(reg);
        }
        if (wasHunted && bolos != null && target.uuid() != null) {
            bolos.resolveFor(target.uuid(),
                    com.dwurdy.straja.domain.model.BoloStatus.RESOLVED, "arrested");
        }
        var cell = blank(sentence.cellId) ? null : store().cell(sentence.cellId);
        if (camp != null && custody.enterJail(target, "prison")) {
            deliverToCamp(target, camp, true);
        } else if (cell != null && custody.enterJail(target, "prison")) {
            teleportToCell(target, cell);
            target.setGameMode("adventure");
        }
        // FUN-7: a recaptured manhunt runner serves extra time. The store
        // object `sentence` came from a different read — persist through the
        // authoritative graph or the penalty evaporates.
        var hunt = manhunts == null ? null : manhunts.huntFor(uuid);
        if (hunt != null) {
            int penalty = Math.max(0, ctx.policies().manhuntRecapturePenaltyDays);
            var fresh = store();
            Sentence stored = null;
            for (Sentence s : fresh.sentences) {
                if (s != null && sentence.id.equals(s.id)) stored = s;
            }
            if (stored != null && penalty > 0) {
                stored.remainingActiveMs += (long) penalty
                        * Math.max(1, ctx.policies().prisonActiveMinutesPerMinecraftDay) * 60_000L;
                ctx.prison().write(fresh);
                sentence.remainingActiveMs = stored.remainingActiveMs;
            }
            manhunts.runnerCaptured(uuid);
            target.tell("Vânătoarea s-a încheiat pentru tine — sentința a fost prelungită.");
        }
        if (wasHunted) {
            target.tell("Ai fost readus în custodie.");
            audit.record(AuditActions.PRISON_RECAPTURE, "", "", sentence.target, uuid,
                    AuditResults.SUCCESS, "sentenceId=" + sentence.id);
        }
    }

    // ------------------------------------------------------------ FUN-7 tamper

    /**
     * Right-click on a cell door with a break tool. Bolt cutters open any
     * tier after a long channel; the crowbar opens WEAK locks quickly.
     * The attempt refuses while a uniformed guard stands inside the
     * detection radius — presence is the counterplay. Returns true when
     * the click was consumed (attempt running, refused, or completed).
     */
    @Override
    public boolean onCellTamper(PlayerGateway breaker, String dimension, int x, int y, int z) {
        if (breaker == null || breaker.uuid() == null || !ctx.policies().prisonEnabled)
            return false;
        var held = breaker.mainHand();
        String tool = held == null || held.isEmpty() ? "" : held.id();
        boolean cutters = "straja:bolt_cutters".equals(tool);
        boolean crowbar = "straja:crowbar".equals(tool);
        boolean lockpick = "straja:lockpick".equals(tool);
        if (!cutters && !crowbar && !lockpick) return false;
        Cell cell = cellAtDoor(dimension, x, y, z);
        if (cell == null) return false; // not a cell door — leave the click alone
        if (players.isOnDutyGuard(breaker)) {
            breaker.refuse("straja.cell.tamper_duty", "straja.remedy.wait");
            return true;
        }
        if (custody.actionBlocked(breaker, "tamper")) return true;
        if (cell.jammedUntil > now()) {
            // FUN-9: a botched pick leaves the mechanism jammed — trades or
            // time free it; nothing opens it meanwhile.
            breaker.refuse("straja.cell.jammed", "straja.remedy.wait");
            return true;
        }
        if (cell.brokenUntil > now()) {
            breaker.tell("Broasca celulei " + cell.id + " a fost spartă — intră liber.");
            return true;
        }
        if (crowbar && !"WEAK".equalsIgnoreCase(cell.lockTier)) {
            breaker.refuse("straja.cell.tamper_tier", "straja.remedy.fix_retry");
            return true;
        }
        String cellKey = cell.id;
        Tamper active = tamperAttempts.get(cellKey);
        String key = breaker.uuid().toString();
        if (active != null) {
            if (active.breakerUuid.equals(key)) {
                long left = Math.max(0, active.completesAt - now()) / 1_000L;
                breaker.tell("Lucrezi la broască… încă " + left + "s. Nu te mișca.");
            } else {
                breaker.refuse("straja.cell.tamper_busy", "straja.remedy.wait");
            }
            return true;
        }
        // One channel per breaker — a player cannot spring every cell at once.
        for (Tamper t : tamperAttempts.values()) {
            if (t.breakerUuid.equals(key)) {
                breaker.refuse("straja.cell.tamper_busy", "straja.remedy.wait");
                return true;
            }
        }
        if (guardNear(cell)) {
            breaker.refuse("straja.cell.tamper_guard", "straja.remedy.wait");
            return true;
        }
        long seconds = lockpick ? ctx.policies().cellPickSeconds
                : crowbar ? ctx.policies().cellTamperQuickSeconds
                : ctx.policies().cellTamperSeconds;
        tamperAttempts.put(cellKey, new Tamper(cellKey, key, breaker.name(),
                now() + Math.max(1, seconds) * 1_000L,
                cell.doorX + 0.5, cell.doorY + 0.5, cell.doorZ + 0.5, tool));
        audit.record(AuditActions.PRISON_BREAK, key, key, cell.id, "",
                AuditResults.SUCCESS, "tamper_start:" + tool);
        breaker.tell("§6Începi să forțezi celula " + cell.id + " — "
                + Math.max(1, seconds) + "s. Stai aproape și stai jos de strajă.");
        return true;
    }

    /** The cell whose door centre is within tamper reach of the click. */
    private Cell cellAtDoor(String dimension, int x, int y, int z) {
        for (Cell cell : store().cells) {
            if (cell == null || !cell.dimension.equals(dimension)) continue;
            int dx = x - cell.doorX, dy = y - cell.doorY, dz = z - cell.doorZ;
            if (dx * dx + dy * dy + dz * dz <= 9) return cell; // 3-block reach
        }
        return null;
    }

    private boolean guardNear(Cell cell) {
        double radius = Math.max(1.0, ctx.policies().manhuntGuardDetectRadius);
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p == null || !cell.dimension.equals(p.dimension())) continue;
            if (!players.isOnDutyGuard(p)) continue;
            double dx = p.x() - cell.doorX, dy = p.y() - cell.doorY, dz = p.z() - cell.doorZ;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    /** Advances tamper channels each tick; completion is atomic + audited. */
    private void tickTampers() {
        if (tamperAttempts.isEmpty()) return;
        var it = tamperAttempts.values().iterator();
        while (it.hasNext()) {
            Tamper t = it.next();
            if (now() < t.completesAt) continue;
            it.remove();
            PlayerGateway breaker = null;
            for (PlayerGateway p : ctx.server().onlinePlayers()) {
                if (p != null && t.breakerUuid.equals(p.uuid() == null ? "" : p.uuid().toString()))
                    breaker = p;
            }
            if (breaker == null || custody.actionBlocked(breaker, "tamper")) continue;
            Cell cell = null;
            var data = store();
            for (Cell c : data.cells) {
                if (c != null && t.cellId.equals(c.id)) cell = c;
            }
            if (cell == null || cell.brokenUntil > now()) continue;
            if (!cell.dimension.equals(breaker.dimension())) continue; // portaled out
            // The tool that opened the channel must still be in hand.
            var heldNow = breaker.mainHand();
            String toolNow = heldNow == null || heldNow.isEmpty() ? "" : heldNow.id();
            boolean toolOk = "straja:bolt_cutters".equals(toolNow)
                    || "straja:lockpick".equals(toolNow)
                    || ("straja:crowbar".equals(toolNow) && "WEAK".equalsIgnoreCase(cell.lockTier));
            if (!toolOk) {
                breaker.tell("Ai scăpat unealta din mână — forțarea a eșuat.");
                continue;
            }
            double dx = breaker.x() - t.x, dy = breaker.y() - t.y, dz = breaker.z() - t.z;
            double range = Math.max(1.0, ctx.policies().cellTamperRange);
            if (dx * dx + dy * dy + dz * dz > range * range) {
                breaker.tell("Te-ai depărtat de ușă — forțarea a eșuat.");
                continue;
            }
            if (guardNear(cell)) {
                breaker.tell("Un străjer s-a apropiat — forțarea a eșuat.");
                continue;
            }
            if ("straja:lockpick".equals(t.tool)
                    && !"WEAK".equalsIgnoreCase(cell.lockTier)
                    && rolls.nextInt(100) < Math.max(0, ctx.policies().cellPickJamPercent)) {
                // FUN-9: a botched pick jams a strong lock — the door stays
                // shut and the jam itself is a detection event.
                cell.jammedUntil = now()
                        + Math.max(1, ctx.policies().cellJammedMinutes) * 60_000L;
                cell.jammedByUuid = t.breakerUuid;
                cell.jammedByName = t.breakerName;
                ctx.prison().write(data);
                audit.record(AuditActions.PRISON_BREAK, t.breakerName, t.breakerUuid,
                        cell.id, "", AuditResults.SUCCESS, "pick_jammed");
                breaker.tell("§cBroasca s-a gripat — uneltul a rămas în mecanism.");
                if (escapes != null) escapes.notice("jam:" + cell.id + ":" + t.breakerUuid,
                        "Broască blocată la celula " + cell.id,
                        "o tentativă de pichiat a lăsat urme",
                        cell.dimension, cell.doorX + 0.5, cell.doorY + 0.5, cell.doorZ + 0.5,
                        "celula " + cell.id, t.breakerUuid, t.breakerName);
                continue;
            }
            long openMinutes = "straja:lockpick".equals(t.tool)
                    ? Math.max(1, ctx.policies().cellPickOpenMinutes)
                    : Math.max(1, ctx.policies().cellBrokenMinutes);
            cell.brokenUntil = now() + openMinutes * 60_000L;
            cell.brokenByUuid = t.breakerUuid;
            cell.brokenByName = t.breakerName;
            ctx.prison().write(data);
            audit.record(AuditActions.PRISON_BREAK, t.breakerName, t.breakerUuid,
                    cell.id, "", AuditResults.SUCCESS,
                    "straja:lockpick".equals(t.tool) ? "pick_complete" : "tamper_complete");
            breaker.tell("§cBroasca a cedat — celula " + cell.id + " e deschisă. Fugi!");
            openManhunt(breaker, cell, data);
        }
    }

    /** A finished break implicates the breaker plus that cell's occupants. */
    private void openManhunt(PlayerGateway breaker, Cell cell, PrisonStore data) {
        if (manhunts == null) return;
        var runners = new ArrayList<com.dwurdy.straja.domain.model.ManhuntRecord.Runner>();
        var br = new com.dwurdy.straja.domain.model.ManhuntRecord.Runner();
        br.uuid = breaker.uuid().toString();
        br.name = breaker.name();
        runners.add(br);
        var assignment = data.assignments.get(cell.id);
        if (assignment != null && assignment.targetUuid != null
                && !assignment.targetUuid.isBlank()
                && !assignment.targetUuid.equals(br.uuid)) {
            // Only an occupant the register still holds IN_CELL rides the
            // hunt — an escorted or already-loose prisoner is not sprung.
            var rr = ctx.prisonerRegister().read().prisoner(assignment.targetUuid);
            if (rr != null && rr.status == PrisonerStatus.IN_CELL) {
                var r = new com.dwurdy.straja.domain.model.ManhuntRecord.Runner();
                r.uuid = assignment.targetUuid;
                r.name = assignment.target;
                r.wasPrisoner = true;
                runners.add(r);
            }
        }
        manhunts.begin(runners, cell.dimension,
                cell.doorX + 0.5, cell.doorY + 0.5, cell.doorZ + 0.5,
                "celula " + cell.id, "cell:" + cell.id);
    }

    /** Resolves the arrest site from an explicit site id or a {@code checkpoint:<id>} mission tag. */
    private com.dwurdy.straja.domain.model.LawCheckpointRecord siteFor(String siteId,
                                                                     String missionId) {
        String id = !blank(siteId) ? siteId
                : missionId != null && missionId.startsWith("checkpoint:")
                        ? missionId.substring("checkpoint:".length()) : "";
        if (blank(id)) return null;
        return ctx.lawCheckpoints().read().checkpoint(id);
    }

    private void teleportToCell(PlayerGateway player, Cell cell) {
        player.teleport(cell.dimension, cell.minX + 1 + 0.5, cell.minY + 1, cell.minZ + 1 + 0.5);
    }

    public boolean release(PlayerGateway actor, PlayerGateway target, String reason) {
        if (target == null) {
            actor.refuse("straja.common.target_offline", "straja.remedy.wait");
            return false;
        }
        if (!actor.isOp() && !players.hasCapability(actor, Capability.EXECUTE_ARRESTS)) {
            actor.refuse("straja.prison.release_rank", "straja.remedy.jailer");
            audit.record(AuditActions.PRISON_RELEASE, actor.name(), uuidOf(actor),
                    target.name(), uuidOf(target),
                    AuditResults.REFUSED, "missing_authority");
            return false;
        }
        var data = store();
        var sentence = activeSentenceFrom(data, target);
        if (sentence == null) {
            actor.refuse("straja.prison.no_sentence", "straja.remedy.jailer", target.name());
            return false;
        }
        // DEBT-2: a jailer release settles the ledger first; an explicit
        // commissioner/op (admin) release is the forced path that bypasses
        // the levy and the debt gate entirely.
        if (!releaseSentence(data, sentence, target, "FORCED_RELEASE", null,
                actor, players.isCommissioner(actor) || actor.isOp())) return false;
        ctx.prison().write(data);
        audit.record(AuditActions.PRISON_RELEASE, actor.name(), uuidOf(actor),
                sentence.target, sentence.targetUuid, AuditResults.SUCCESS,
                "FORCED_RELEASE reason=" + (reason == null ? "" : reason));
        return true;
    }

    private boolean releaseSentence(PrisonStore data, Sentence sentence, PlayerGateway target, String reason) {
        return releaseSentence(data, sentence, target, reason, null);
    }

    private boolean releaseSentence(PrisonStore data, Sentence sentence, PlayerGateway target,
                                    String reason,
                                    com.dwurdy.straja.domain.model.LaborCampRecord laborCamp) {
        return releaseSentence(data, sentence, target, reason, laborCamp, null, false);
    }

    /**
     * Core release path. {@code laborCamp} overrides the exit point (the
     * camp's release spawn) and — when the reason is {@code SERVED_LABOR} —
     * the terminal register status. Locker restore, game-mode restore, BOLO
     * clear and audit hooks are identical to a cell release.
     *
     * <p>DEBT-2: a normal release settles the ledger before custody or
     * locker state is touched — levy, then the debt gate. {@code forced}
     * marks an explicit admin release (commissioner), which bypasses both.
     */
    private boolean releaseSentence(PrisonStore data, Sentence sentence, PlayerGateway target,
                                    String reason,
                                    com.dwurdy.straja.domain.model.LaborCampRecord laborCamp,
                                    PlayerGateway releaser, boolean forced) {
        if (sentence == null || java.util.Set.of("SERVED", "FORCED_RELEASE", "CANCELLED")
                .contains(sentence.status)) return false;
        if (!forced && !debtGateAllows(data, sentence, target, releaser)) return false;
        if (target != null && !custody.releaseFromJail(target)) return false;
        sentence.status = "FORCED_RELEASE".equals(reason) ? "FORCED_RELEASE" : "SERVED";
        sentence.servedAt = now();
        sentence.releaseReason = reason == null ? "SERVED" : reason;
        if ("SERVED".equals(sentence.status)) {
            for (var hook : sentenceCompletedHooks) hook.accept(sentence, sentence.releaseReason);
        }
        if (!sentence.cellId.isEmpty()) data.assignments.remove(sentence.cellId);
        // M4: an official release restores belongings, game mode and status.
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(sentence.targetUuid);
        if (laborCamp == null && rec != null && rec.assignedCampId != null
                && !rec.assignedCampId.isBlank() && camps != null) {
            // Any release path for a camp-assigned prisoner (manual jailer
            // release included) exits at the camp's release point.
            laborCamp = camps.camp(rec.assignedCampId);
        }
        if (rec != null && !rec.status.isReleased()) {
            rec.status = "SERVED_LABOR".equals(sentence.releaseReason)
                    ? PrisonerStatus.SERVED_LABOR : PrisonerStatus.RELEASED;
            rec.releasedAt = now();
            rec.assignedCampId = "";
            if (seizure != null) seizure.releaseLocker(reg, target, rec);
            ctx.prisonerRegister().write(reg);
        }
        if (bolos != null) {
            // Key by uuid or recorded name — a corrupt/legacy uuid must not
            // leave ACTIVE marks hunting a lawfully released player.
            bolos.resolveForSubject(sentence.targetUuid,
                    com.dwurdy.straja.domain.model.BoloStatus.RESOLVED, "released");
            bolos.resolveForSubject(sentence.target,
                    com.dwurdy.straja.domain.model.BoloStatus.RESOLVED, "released");
        }
        // FUN-7: a lawful release lifts the runner out of any live manhunt —
        // otherwise the hunt would cool into a warrant on a free citizen.
        if (manhunts != null && manhunts.huntFor(sentence.targetUuid) != null) {
            manhunts.runnerCaptured(sentence.targetUuid);
        }
        if (target != null) {
            target.setGameMode(rec == null || blank(rec.priorGameMode)
                    ? "survival" : rec.priorGameMode);
            if (laborCamp != null && laborCamp.releaseSpawn != null) {
                var p = laborCamp.releaseSpawn;
                target.teleport(p.dimension(), p.x() + 0.5, p.y(), p.z() + 0.5);
            } else {
                teleportToRelease(target);
            }
            if (laborCamp != null) {
                target.tell("Ți-ai plătit libertatea prin muncă. Ești eliberat din lagăr.");
            } else {
                target.tell("FORCED_RELEASE".equals(sentence.status)
                        ? "Ai fost eliberat administrativ din celulă."
                        : "Ți-ai executat sentința. Ești eliberat din celulă.");
            }
        }
        return true;
    }

    private void teleportToRelease(PlayerGateway player) {
        SetupData.Location point = ctx.setup().read().location("prisonRelease");
        if (point != null) player.teleport(point.dimension, point.x + 0.5, point.y, point.z + 0.5);
    }

    // ---------------------------------------------------- LAW-006 camps

    /**
     * Resolves an arrest site's {@code CAMP:<id>} destination. Unknown or
     * dangling camp ids degrade to the normal cell flow — an unlinked
     * destination must not strand a prisoner outside custody bookkeeping.
     */
    private com.dwurdy.straja.domain.model.LaborCampRecord campDestination(
            com.dwurdy.straja.domain.model.LawCheckpointRecord site) {
        if (site == null || site.arrestDestination == null || camps == null) return null;
        String dest = site.arrestDestination.trim();
        if (!dest.toUpperCase(java.util.Locale.ROOT).startsWith("CAMP:")) return null;
        return camps.camp(dest.substring(5).trim());
    }

    /**
     * Lands a prisoner inside the camp — the intake spawn when present,
     * the boundary centre otherwise. Falls back to no teleport when the camp
     * carries no usable geometry (bounds checks still apply).
     */
    /**
     * Camp delivery: teleport to the camp spawn AND restore survival — cell
     * custody runs in adventure mode, but a laborer who cannot swing a pick
     * can never earn the freedom price. {@code releaseSentence} restores
     * {@code priorGameMode} afterwards.
     */
    private void deliverToCamp(PlayerGateway player,
                               com.dwurdy.straja.domain.model.LaborCampRecord camp,
                               boolean intake) {
        teleportToCampSpawn(player, camp, intake);
        if (!"survival".equalsIgnoreCase(player.gameModeName())) {
            player.setGameMode("survival");
        }
    }

    private void teleportToCampSpawn(PlayerGateway player,
                                     com.dwurdy.straja.domain.model.LaborCampRecord camp,
                                     boolean intake) {
        com.dwurdy.straja.domain.model.StoragePoint point = intake
                ? (camp.intakeSpawn != null ? camp.intakeSpawn : camp.dormitorySpawn)
                : (camp.dormitorySpawn != null ? camp.dormitorySpawn : camp.intakeSpawn);
        if (point != null) {
            player.teleport(point.dimension(), point.x() + 0.5, point.y(), point.z() + 0.5);
            return;
        }
        var b = camp.boundary;
        if (b != null) {
            player.teleport(b.dimension(),
                    (b.minX() + b.maxX()) / 2.0 + 0.5, b.maxY(),
                    (b.minZ() + b.maxZ()) / 2.0 + 0.5);
        }
    }

    /**
     * {@code /straja camp transfer <player> <camp>} — moves an active
     * prisoner into camp custody: frees any assigned cell bunk, flips the
     * register to IN_CAMP, and delivers the body at the camp intake. No
     * fugitive alert fires — this is an official move, not a breach.
     */
    public boolean transferToCamp(PlayerGateway actor, PlayerGateway target, String campId) {
        if (!players.hasCapability(actor, Capability.EXECUTE_ARRESTS)) {
            actor.refuse("straja.prison.release_rank", "straja.remedy.jailer");
            audit.record(AuditActions.CAMP_TRANSFER, actor.name(), uuidOf(actor),
                    target == null ? campId : target.name(), "",
                    AuditResults.REFUSED, "missing_authority");
            return false;
        }
        if (target == null || !target.isOnline()) {
            actor.refuse("straja.common.target_offline", "straja.remedy.wait");
            return false;
        }
        var camp = camps == null ? null : camps.camp(campId);
        if (camp == null) {
            actor.refuse("straja.camp.unknown", "straja.remedy.fix_retry", campId);
            return false;
        }
        var data = store();
        var sentence = activeSentenceFrom(data, target);
        if (sentence == null) {
            actor.refuse("straja.prison.no_sentence", "straja.remedy.jailer", target.name());
            return false;
        }
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(sentence.targetUuid);
        if (rec != null && rec.status == PrisonerStatus.IN_CAMP
                && camp.id.equals(rec.assignedCampId)) {
            // Idempotent: already assigned to this camp — nothing to move.
            actor.tellKey("straja.camp.already_there", target.name(), camp.id);
            return false;
        }
        // FUN-8: a rideable transfer orders an escorted ride instead of a
        // teleport — the prisoner waits for a real escort.
        if (tryRideOrder(actor, target, camp, sentence, "manual_order")) {
            actor.tell("Transfer ordonat: " + target.name() + " → " + camp.id
                    + ". Apelul de escortă e pe tabloul de dispatch.");
            return true;
        }
        // DEBT-2: the levy sweeps custody coins before the prisoner boards.
        if (debt != null && ctx.policies().debtEnabled) debt.levy(sentence.targetUuid);
        if (custody.enterJail(target, "prison")) {
            if (rec != null && rec.status == PrisonerStatus.FUGITIVE && bolos != null
                    && target.uuid() != null) {
                bolos.resolveFor(target.uuid(),
                        com.dwurdy.straja.domain.model.BoloStatus.RESOLVED, "arrested");
            }
            if (rec == null) {
                rec = new com.dwurdy.straja.domain.model.PrisonerRegisterRecord(
                        sentence.targetUuid, sentence.target, "labor transfer");
                reg.put(rec);
            }
            rec.status = PrisonerStatus.IN_CAMP;
            rec.assignedCampId = camp.id;
            ctx.prisonerRegister().write(reg);
            // Free the bunk — a camp-bound prisoner holds no cell.
            if (!sentence.cellId.isEmpty()) {
                data.assignments.remove(sentence.cellId);
                sentence.cellId = "";
            }
            if ("WAITING_CELL".equals(sentence.status)) {
                sentence.status = "ACTIVE";
                data.waitlist.removeIf(e -> e != null
                        && sentence.id.equals(e.sentenceId));
            }
            ctx.prison().write(data);
            deliverToCamp(target, camp, true);
            target.tellKey("straja.camp.transferred", camp.name.isBlank() ? camp.id : camp.name);
            actor.tellKey("straja.camp.transfer_done", target.name(), camp.id);
            audit.record(AuditActions.CAMP_TRANSFER, actor.name(), uuidOf(actor),
                    target.name(), sentence.targetUuid, AuditResults.SUCCESS, "camp=" + camp.id);
            return true;
        }
        actor.refuse("straja.prison.handover_retry", "straja.remedy.jailer");
        return false;
    }

    /**
     * #231 bail release: a paid bounty-capture fine frees the prisoner
     * immediately — no officer authority needed, the state itself releases.
     * Works online and offline (offline releases park lockers as pending).
     */
    public boolean releaseForBail(String targetUuid) {
        if (targetUuid == null || targetUuid.isBlank()) return false;
        var data = store();
        for (var sentence : data.sentences) {
            if (sentence == null
                    || (!"ACTIVE".equals(sentence.status)
                            && !"WAITING_CELL".equals(sentence.status))
                    || !targetUuid.equals(sentence.targetUuid)) continue;
            var target = findFor(sentence);
            if (!releaseSentence(data, sentence, target, "BAIL_PAID")) return false;
            ctx.prison().write(data);
            audit.record(AuditActions.PRISON_RELEASE, "bounty_bail", "",
                    sentence.target, targetUuid, AuditResults.SUCCESS, "BAIL_PAID");
            return true;
        }
        return false;
    }

    /**
     * FUN-11 parole release: a decided hearing frees the prisoner into parole
     * — no officer authority needed at this step, the hearing artifact is the
     * authority. The register lands on PAROLED (free, under conditions) rather
     * than RELEASED so the marker stays visible until the window completes.
     */
    public boolean releaseForParole(String targetUuid, String hearingId) {
        if (targetUuid == null || targetUuid.isBlank()) return false;
        var data = store();
        for (var sentence : data.sentences) {
            if (sentence == null
                    || (!"ACTIVE".equals(sentence.status)
                            && !"WAITING_CELL".equals(sentence.status))
                    || !targetUuid.equals(sentence.targetUuid)) continue;
            var target = findFor(sentence);
            // The hearing decision weighed the ledger — parole releases past
            // the debt gate the way a commissioner's considered release does.
            if (!releaseSentence(data, sentence, target, "PAROLED", null, null, true)) {
                return false;
            }
            ctx.prison().write(data);
            var reg = ctx.prisonerRegister().read();
            var rec = reg.prisoner(targetUuid);
            if (rec != null && rec.status == PrisonerStatus.RELEASED) {
                rec.status = PrisonerStatus.PAROLED;
                ctx.prisonerRegister().write(reg);
            }
            audit.record(AuditActions.PRISON_RELEASE, "parole", "",
                    sentence.target, targetUuid, AuditResults.SUCCESS,
                    "PAROLED hearing=" + (hearingId == null ? "" : hearingId));
            return true;
        }
        return false;
    }

    /**
     * #231 system camp transfer: an unpaid bail window lapsing sends the
     * prisoner to the mines without an officer present. Online prisoners are
     * delivered at the intake point; offline ones get the register link and
     * land at intake on next login (the IN_CAMP relog heal path).
     *
     * <p>DEBT-2: custody coins settle the ledger before the prisoner boards.
     */
    public boolean systemTransferToCamp(String targetUuid, String campId) {
        // FUN-8: an ordered system transfer becomes a ride when one can run —
        // online prisoner, members awake, rideable distance. Otherwise the
        // instant path applies (the FUN-8 policy fallback).
        if (tryRideOrderByUuid(null, targetUuid, campId, "system")) return true;
        return applySystemCampTransfer(targetUuid, campId, "bounty_bail");
    }

    /**
     * FUN-8 ride settle: the escorted prisoner reached the wire — applies
     * the camp custody mutation exactly as the instant path would.
     */
    public boolean completeRideDelivery(String targetUuid, String campId, String rideId) {
        return applySystemCampTransfer(targetUuid, campId, "ride:" + rideId);
    }

    private boolean applySystemCampTransfer(String targetUuid, String campId, String source) {
        if (camps == null || targetUuid == null || targetUuid.isBlank()) return false;
        var camp = camps.camp(campId);
        if (camp == null) return false;
        var data = store();
        Sentence sentence = null;
        for (var s : data.sentences) {
            if (s != null && ("ACTIVE".equals(s.status)
                            || "WAITING_CELL".equals(s.status))
                    && targetUuid.equals(s.targetUuid)) {
                sentence = s;
                break;
            }
        }
        if (sentence == null) return false;
        // DEBT-2: the levy sweeps custody coins before departure. The debt
        // gate calls applyCampTransfer directly, so a blocked release can
        // never re-enter the gate through this path.
        if (debt != null && ctx.policies().debtEnabled) debt.levy(targetUuid);
        if (!applyCampTransfer(data, sentence, findFor(sentence), camp, source)) {
            return false;
        }
        ctx.prison().write(data);
        return true;
    }

    /**
     * FUN-8 ride feasibility: enabled, sentenced prisoner online and not
     * already chained, a member awake to take the call, and a route long
     * enough to be a ride. Anything missing falls back to instant transfer.
     */
    private boolean tryRideOrderByUuid(PlayerGateway actor, String targetUuid,
                                       String campId, String reason) {
        var target = findByUuid(targetUuid);
        if (target == null || camps == null) return false;
        var camp = camps.camp(campId);
        if (camp == null) return false;
        var sentence = ctx.prison().read().activeSentenceFor(targetUuid);
        return tryRideOrder(actor, target, camp, sentence, reason);
    }

    private boolean tryRideOrder(PlayerGateway actor, PlayerGateway target,
                                 com.dwurdy.straja.domain.model.LaborCampRecord camp,
                                 Sentence sentence, String reason) {
        if (transfers == null || !ctx.policies().transferRideEnabled
                || target == null || sentence == null) return false;
        var rec = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
        if (rec == null || (rec.status != PrisonerStatus.IN_CELL
                && rec.status != PrisonerStatus.IN_CAMP)) return false;
        if (custody.isCuffed(target)) return false;
        var spawn = camp.intakeSpawn != null ? camp.intakeSpawn : camp.dormitorySpawn;
        double dx = (spawn != null ? spawn.x() : 0) - target.x();
        double dz = (spawn != null ? spawn.z() : 0) - target.z();
        if (!target.dimension().equals(camp.dimension)
                || Math.sqrt(dx * dx + dz * dz) < Math.max(0, ctx.policies().transferMinRideBlocks)) {
            return false; // same-station hops stay instant
        }
        if (!anyMemberAwake(target)) return false;
        String origin = sentence.cellId.isBlank() ? rec.assignedCampId : "celula " + sentence.cellId;
        return transfers.order(actor, target, camp, sentence.id,
                origin == null || origin.isBlank() ? "custodie" : origin, reason) != null;
    }

    /** Zero online members → the instant fallback keeps custody moves atomic. */
    private boolean anyMemberAwake(PlayerGateway exclude) {
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p == null || p.uuid() == null || p == exclude) continue;
            if (players.isCommissioner(p) || players.state(p).rank
                    >= com.dwurdy.straja.domain.model.Rank.GUARD.level()) return true;
        }
        return false;
    }

    private PlayerGateway findByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p != null && p.uuid() != null && uuid.equalsIgnoreCase(p.uuid().toString()))
                return p;
        }
        return null;
    }

    /**
     * FUN-8: a broken ride marks the chained prisoner fugitive (warrant +
     * register). Uuid-keyed — an offline prisoner at the break still gets
     * the warrant; the online gateway only carries the escape tell.
     */
    public void rideChainBroke(String prisonerUuid, PlayerGateway onlineTarget, String rideId) {
        flagFugitive(prisonerUuid, onlineTarget, "ride_broken ride=" + rideId);
    }

    /**
     * FUN-8/9: uuid-keyed fugitive mark shared by ride breaks and escape
     * detections — warrant + register land even when the body is offline.
     */
    public void flagFugitive(String prisonerUuid, PlayerGateway onlineTarget, String detail) {
        if (prisonerUuid == null || prisonerUuid.isBlank()) return;
        var sentence = ctx.prison().read().activeSentenceFor(prisonerUuid);
        if (sentence == null) return;
        markFugitive(sentence, onlineTarget);
        audit.record(AuditActions.PRISON_FUGITIVE, "transfer", "", sentence.target,
                sentence.targetUuid, AuditResults.SUCCESS, detail == null ? "fugitive" : detail);
    }

    /**
     * FUN-9: detection consequence — extends the active sentence when a
     * tunnel is found or contraband is seized. No-op without an open
     * sentence; returns false so callers can tell "already gone".
     */
    public boolean extendSentence(String prisonerUuid, long extraMs, String reason) {
        if (prisonerUuid == null || prisonerUuid.isBlank() || extraMs <= 0) return false;
        var data = store();
        var sentence = data.activeSentenceFor(prisonerUuid);
        if (sentence == null) return false;
        sentence.remainingActiveMs += extraMs;
        ctx.prison().write(data);
        audit.record(AuditActions.PRISON_RECAPTURE, "system", "", sentence.target, prisonerUuid,
                AuditResults.SUCCESS, "extended +" + (extraMs / 60_000L) + "m " + reason);
        return true;
    }

    /**
     * FUN-9: a paid guard unlocks the payer's cell for a short window —
     * same field as a sprung lock, cleared jam included.
     */
    public boolean bribeUnlock(String prisonerUuid, int openSeconds) {
        if (prisonerUuid == null || prisonerUuid.isBlank()) return false;
        var data = store();
        var sentence = data.activeSentenceFor(prisonerUuid);
        if (sentence == null || sentence.cellId.isBlank()) return false;
        var cell = data.cell(sentence.cellId);
        if (cell == null) return false;
        long open = now() + Math.max(5, openSeconds) * 1_000L;
        if (cell.brokenUntil < open) {
            cell.brokenUntil = open;
            cell.brokenByUuid = prisonerUuid;
            cell.brokenByName = sentence.target;
        }
        cell.jammedUntil = 0;
        ctx.prison().write(data);
        return true;
    }

    /**
     * Shared camp-transfer mutation: the register claims IN_CAMP, any cell
     * bunk is freed, WAITING_CELL promotes to ACTIVE, and an online body
     * lands at the camp intake. Mutates the caller's store — the caller
     * owns the prison write. The debt gate routes here so its internal
     * transfer can never recurse into the release check. Returns true when
     * the prisoner ends assigned to the camp (moved or already there).
     */
    private boolean applyCampTransfer(PrisonStore data, Sentence sentence,
                                      PlayerGateway target,
                                      com.dwurdy.straja.domain.model.LaborCampRecord camp,
                                      String source) {
        if (sentence == null || camp == null) return false;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(sentence.targetUuid);
        if (rec != null && rec.status == PrisonerStatus.IN_CAMP
                && camp.id.equals(rec.assignedCampId)) return true;
        if (rec == null) {
            rec = new com.dwurdy.straja.domain.model.PrisonerRegisterRecord(
                    sentence.targetUuid, sentence.target, source);
            reg.put(rec);
        }
        rec.status = PrisonerStatus.IN_CAMP;
        rec.assignedCampId = camp.id;
        ctx.prisonerRegister().write(reg);
        if (!sentence.cellId.isEmpty()) {
            data.assignments.remove(sentence.cellId);
            sentence.cellId = "";
        }
        if ("WAITING_CELL".equals(sentence.status)) {
            sentence.status = "ACTIVE";
            data.waitlist.removeIf(e -> e != null
                    && sentence.id != null && sentence.id.equals(e.sentenceId));
        }
        if (target != null && custody.enterJail(target, "prison")) {
            deliverToCamp(target, camp, true);
            target.tellKey("straja.camp.transferred",
                    camp.name.isBlank() ? camp.id : camp.name);
        }
        audit.record(AuditActions.CAMP_TRANSFER, "system", "", sentence.target,
                sentence.targetUuid, AuditResults.SUCCESS, "camp=" + camp.id + " source=" + source);
        return true;
    }

    // ------------------------------------------------------------ DEBT-2 gate

    /** Suppression window for repeated gate notices (tick-driven retries). */
    private static final long DEBT_GATE_NOTICE_MS = 60_000L;
    private record GateNotice(int owed, long at) {}
    private final java.util.Map<String, GateNotice> debtGateNotice = new java.util.HashMap<>();

    /**
     * DEBT-2 (#235) release gate: levies custody coins, then re-reads the
     * balance. At or under {@code debtReleaseBlockThreshold} the release
     * proceeds; over it, {@code debtOnBlocked=CAMP} diverts the release into
     * a default-camp transfer on the caller's store (an internal call that
     * cannot re-enter this gate), while CELL refuses the releaser and keeps
     * custody. Either way the prisoner is handed a written statement.
     * Returns true only when release may proceed.
     */
    private boolean debtGateAllows(PrisonStore data, Sentence sentence,
                                   PlayerGateway target, PlayerGateway releaser) {
        if (debt == null || !ctx.policies().debtEnabled
                || sentence.targetUuid == null || sentence.targetUuid.isBlank()) return true;
        debt.levy(sentence.targetUuid);
        int owed = debt.outstandingDebt(sentence.targetUuid);
        int threshold = Math.max(0, ctx.policies().debtReleaseBlockThreshold);
        if (owed <= threshold) {
            debtGateNotice.remove(sentence.targetUuid);
            return true;
        }
        boolean notify = shouldNotifyGate(sentence.targetUuid, owed);
        String actor = releaser == null ? "system" : releaser.name();
        String actorUuid = releaser == null ? "" : uuidOf(releaser);
        if ("CAMP".equalsIgnoreCase(ctx.policies().debtOnBlocked)) {
            var camp = debtDefaultCamp();
            // FUN-8: the debt gate orders a ride when one can run — the
            // prisoner waits in custody for a real escort.
            boolean debtRide = camp != null && target != null
                    && tryRideOrder(releaser, target, camp, sentence, "debt_gate");
            if (debtRide || (camp != null && applyCampTransfer(data, sentence, target, camp, "debt_gate"))) {
                // The transfer mutates the caller's store — persist it here
                // so failure-returning callers cannot lose the camp claim.
                ctx.prison().write(data);
                String campName = camp.name.isBlank() ? camp.id : camp.name;
                if (releaser != null) {
                    releaser.refuse("straja.debt.release_to_camp",
                            "straja.remedy.debt_release",
                            sentence.target, owed, campName);
                }
                if (notify) debt.notifyTransferOrder(sentence.targetUuid, target, owed, campName);
                audit.record(AuditActions.PRISON_RELEASE, actor, actorUuid, sentence.target,
                        sentence.targetUuid, AuditResults.REFUSED,
                        "debt_gate camp=" + camp.id + " owed=" + owed
                                + " threshold=" + threshold);
                return false;
            }
            // No camp exists to take them — fall through to a custody refusal.
        }
        if (releaser != null) {
            releaser.refuse("straja.debt.release_blocked",
                    "straja.remedy.debt_release", owed, threshold);
        }
        if (notify) debt.notifyReleaseDenied(sentence.targetUuid, target, owed, threshold);
        audit.record(AuditActions.PRISON_RELEASE, actor, actorUuid, sentence.target,
                sentence.targetUuid, AuditResults.REFUSED,
                "debt_gate cell owed=" + owed + " threshold=" + threshold);
        return false;
    }

    /** The camp a debt-blocked release diverts to: bounty.defaultCampId, else the first registered. */
    private com.dwurdy.straja.domain.model.LaborCampRecord debtDefaultCamp() {
        if (camps == null) return null;
        String campId = ctx.policies().bountyDefaultCampId;
        if (campId == null || campId.isBlank()) {
            var registered = ctx.laborCamps().read().camps();
            campId = registered.isEmpty() ? "" : registered.keySet().iterator().next();
        }
        return campId.isBlank() ? null : camps.camp(campId);
    }

    /** Gate statements re-send at most once a minute while the owed amount is unchanged. */
    private boolean shouldNotifyGate(String uuid, int owed) {
        var last = debtGateNotice.get(uuid);
        long now = now();
        if (last != null && last.owed() == owed && now - last.at() < DEBT_GATE_NOTICE_MS) {
            return false;
        }
        debtGateNotice.put(uuid, new GateNotice(owed, now));
        return true;
    }

    /**
     * Automatic labor release: when a camp prisoner's labor account reaches
     * the freedom price the sentence closes as served, the register marks
     * SERVED_LABOR, belongings come back and the body exits at the camp's
     * release point. Runs every tick for camp prisoners and directly after
     * each quartermaster credit — idempotent once terminal.
     */
    public boolean checkLaborRelease(String targetUuid) {
        if (camps == null || targetUuid == null || targetUuid.isBlank()) return false;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(targetUuid);
        if (rec == null || rec.status != PrisonerStatus.IN_CAMP
                || rec.assignedCampId == null || rec.assignedCampId.isBlank()) return false;
        var camp = camps.camp(rec.assignedCampId);
        if (camp == null) return false;
        long price = camps.freedomPrice(camp, rec);
        if (price <= 0 || rec.laborAccount < price) return false;
        var data = store();
        for (var sentence : data.sentences) {
            if (sentence == null || !"ACTIVE".equals(sentence.status)
                    || !targetUuid.equals(sentence.targetUuid)) continue;
            var target = findFor(sentence);
            if (releaseSentence(data, sentence, target, "SERVED_LABOR", camp)) {
                ctx.prison().write(data);
                audit.record(AuditActions.CAMP_LABOR_RELEASE, "system", "",
                        sentence.target, targetUuid, AuditResults.SUCCESS,
                        "camp=" + camp.id + " paid=" + rec.laborAccount
                                + " price=" + price);
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Releases a sentenced player by name or UUID without requiring them
     * online — locker belongings are parked under {@code pendingLockers} and
     * the rest of the release finishes at their next login.
     */
    public boolean releaseById(PlayerGateway actor, String targetId) {
        if (!actor.isOp() && !players.hasCapability(actor, Capability.EXECUTE_ARRESTS)) {
            actor.refuse("straja.prison.release_rank", "straja.remedy.jailer");
            audit.record(AuditActions.PRISON_RELEASE, actor.name(), uuidOf(actor),
                    targetId, "", AuditResults.REFUSED, "missing_authority");
            return false;
        }
        var online = ctx.server().findPlayer(targetId);
        if (online != null) return release(actor, online, "command");
        var data = store();
        Sentence sentence = null;
        for (var s : data.sentences) {
            if (s == null || (!"ACTIVE".equals(s.status)
                    && !"WAITING_CELL".equals(s.status))) continue;
            if (targetId.equals(s.targetUuid) || targetId.equalsIgnoreCase(s.target)) {
                sentence = s;
                break;
            }
        }
        if (sentence == null) {
            actor.refuse("straja.prison.no_sentence", "straja.remedy.jailer", targetId);
            return false;
        }
        // DEBT-2: same rule as the online path — jailer release is gated,
        // a commissioner/op release is the forced bypass.
        if (!releaseSentence(data, sentence, null, "FORCED_RELEASE", null,
                actor, players.isCommissioner(actor) || actor.isOp())) return false;
        ctx.prison().write(data);
        actor.tellKey("straja.prison.released_offline", sentence.target);
        audit.record(AuditActions.PRISON_RELEASE, actor.name(), uuidOf(actor),
                sentence.target, sentence.targetUuid, AuditResults.SUCCESS,
                "FORCED_RELEASE offline targetId=" + targetId);
        return true;
    }

    /**
     * Death is not an exit from detention: a respawning prisoner with an
     * active sentence is re-delivered to their cell instead of waking at
     * spawn and drifting into an automatic FUGITIVE mark.
     */
    public void onRespawn(PlayerGateway player) {
        var data = store();
        var sentence = activeSentenceFrom(data, player);
        if (sentence == null || !"ACTIVE".equals(sentence.status)) return;
        var rec = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
        if (rec != null && (rec.status == PrisonerStatus.FUGITIVE
                || rec.status.isReleased())) return;
        // LAW-006: a camp prisoner respawns at the camp dormitory — death
        // inside the perimeter never drops custody or releases them.
        if (rec != null && rec.status == PrisonerStatus.IN_CAMP
                && rec.assignedCampId != null && !rec.assignedCampId.isBlank()
                && camps != null) {
            var camp = camps.camp(rec.assignedCampId);
            if (camp != null) {
                if (custody.enterJail(player, "prison")) {
                    deliverToCamp(player, camp, false);
                    player.tell("Te-ai trezit în lagăr — detenția continuă.");
                }
                return;
            }
        }
        var cell = blank(sentence.cellId) ? null : data.cell(sentence.cellId);
        if (cell == null) return;
        if (custody.enterJail(player, "prison")) {
            teleportToCell(player, cell);
            player.setGameMode("adventure");
            player.tell("Ai fost întors în celulă — detenția continuă.");
        }
    }

    /**
     * Finishes a release that ran while the prisoner was offline: clears a
     * residual JAILED canonical state and restores the captured game mode.
     * Harmless for players with no pending release.
     */
    private void finishReleaseRecovery(PlayerGateway player) {
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(player.uuid().toString());
        if (rec != null && rec.status.isReleased()
                && !blank(rec.priorGameMode) && "adventure".equals(player.gameModeName())) {
            player.setGameMode(rec.priorGameMode);
            rec.priorGameMode = "";
            ctx.prisonerRegister().write(reg);
        }
        custody.releaseFromJail(player);
    }

    public void status(PlayerGateway player) {
        var sentence = activeSentence(player);
        if (sentence == null) {
            player.refuse("straja.prison.self_no_sentence", "straja.remedy.jailer");
            return;
        }
        player.tell("Detenție: " + sentence.status + " | celulă: "
                + (sentence.cellId.isEmpty() ? "așteptare" : sentence.cellId)
                + " | timp activ rămas: " + Math.ceil(sentence.remainingActiveMs / 60000.0) + " minute.");
        // FUN-10: the prisoner sees the account and the standing parole reads.
        var rec = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
        if (rec != null) {
            int doneJobs = ctx.jobs() == null ? 0
                    : ctx.jobs().read().completedCount(sentence.targetUuid);
            player.tell("Cont de muncă: " + rec.laborAccount
                    + " | reputație: " + rec.standing
                    + " | cote încheiate: " + doneJobs);
        }
    }

    /**
     * KJS-1 — legacy {@code strajaprison jailed|record} output: register rows
     * as display lines for a command source (console-safe, no player context).
     * {@code nameFilter} null/blank returns every row; otherwise matches
     * detainee name (substring, case-insensitive) or exact uuid.
     */
    public java.util.List<String> registerLines(String nameFilter) {
        var register = ctx.prisonerRegister().read();
        String filter = nameFilter == null ? ""
                : nameFilter.trim().toLowerCase(java.util.Locale.ROOT);
        var lines = new java.util.ArrayList<String>();
        for (var rec : register.prisoners().values()) {
            if (rec == null) continue;
            if (!filter.isEmpty()) {
                boolean nameMatch = rec.detaineeName != null
                        && rec.detaineeName.toLowerCase(java.util.Locale.ROOT).contains(filter);
                boolean uuidMatch = rec.detaineeUuid != null
                        && rec.detaineeUuid.equalsIgnoreCase(filter);
                if (!nameMatch && !uuidMatch) continue;
            }
            lines.add((blank(rec.detaineeName) ? rec.detaineeUuid : rec.detaineeName)
                    + " — " + rec.status
                    + (rec.detentionReason == null || rec.detentionReason.isBlank()
                            ? "" : " (" + rec.detentionReason + ")")
                    + (rec.assignedCellId == null || rec.assignedCellId.isBlank()
                            ? "" : " celulă=" + rec.assignedCellId)
                    + (rec.assignedCampId == null || rec.assignedCampId.isBlank()
                            ? "" : " lagăr=" + rec.assignedCampId)
                    + " amenzi=" + rec.outstandingFines
                    + " areste=" + rec.arrestCount);
        }
        return lines;
    }

    /**
     * Login recovery: waiting sentences retry the waitlist; an active sentence
     * with a valid assigned cell teleports the player back inside. A missing or
     * mismatched cell assignment fails closed to WAITING_CELL with exactly one
     * waitlist entry.
     */
    public void recoverOnLogin(PlayerGateway player) {
        // LAW-001: reconcile name-anchored legacy prisoner records and
        // pending locker reservations to the real online UUID.
        String name = player.name();
        String uuid = player.uuid().toString();
        if (name != null && !name.isBlank()) {
            var register = ctx.prisonerRegister().read();
            String legacyId = com.dwurdy.straja.domain.model.PrisonerRegisterStore.legacyUuid(name);
            // Adoption reconciles legacy name-anchored rows only: a record
            // already keyed to a real uuid belongs to that account/session and
            // must never be re-keyed by a same-named joiner.
            var nameMatch = register.prisonerByName(name);
            boolean adopted = nameMatch != null && legacyId.equals(nameMatch.detaineeUuid);
            if (adopted) register.adoptByName(name, uuid);
            boolean lockersMoved = register.rekeyPendingLockers(legacyId, uuid);
            boolean noticesMoved = register.rekeyPendingNotices(legacyId, uuid);
            if (adopted || lockersMoved || noticesMoved) ctx.prisonerRegister().write(register);
        }
        // Released-while-offline prisoners collect locker belongings at login.
        if (seizure != null) seizure.deliverPendingLockers(player);
        // Book notices queued while offline land beside the locker belongings.
        if (debt != null) debt.deliverPendingNotices(player);
        var data = store();
        var sentence = activeSentenceFrom(data, player);
        if (sentence == null || !"WAITING_CELL".equals(sentence.status)
                && !"ACTIVE".equals(sentence.status)) {
            finishReleaseRecovery(player);
            return;
        }
        if (sentence.id == null || sentence.id.isBlank()
                || (blank(sentence.targetUuid) && blank(sentence.target))) {
            // An active sentence without verifiable identity must not drive
            // teleports or assignments: cancel it and release anything claimed.
            sentence.status = "CANCELLED";
            releaseInvalidAssignments(data, sentence, player);
            ctx.prison().write(data);
            audit.record(AuditActions.PRISON_RECOVER, player.name(), uuidOf(player),
                    sentence.target, sentence.targetUuid, AuditResults.SUCCESS,
                    "malformed_sentence_cancelled");
            return;
        }
        if ("WAITING_CELL".equals(sentence.status)) {
            if (repairWaitlist(data, player, sentence)) ctx.prison().write(data);
            processWaitlist();
            data = store();
            sentence = activeSentenceFrom(data, player);
        }
        if (sentence == null || !"ACTIVE".equals(sentence.status)) return;
        // A relogged fugitive is not teleported back — they escaped; the
        // register (or their out-of-bounds position) marks them on next tick.
        // An ESCORTED prisoner relogging mid-escort is likewise left in place:
        // the tether (or the breach rule, if the officer is gone) resolves it.
        var regRecord = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
        if (regRecord != null && (regRecord.status == PrisonerStatus.FUGITIVE
                || regRecord.status == PrisonerStatus.ESCORTED)) return;
        // LAW-006: a camp prisoner relogs back into camp custody — intake
        // delivery closes any "logged out past the wire" escape vector. A
        // dangling bunk claim (from pre-transfer data) is freed, never
        // requeued to a cell.
        if (regRecord != null && regRecord.status == PrisonerStatus.IN_CAMP
                && !blank(regRecord.assignedCampId) && camps != null) {
            var camp = camps.camp(regRecord.assignedCampId);
            if (camp != null) {
                if (!sentence.cellId.isEmpty()) {
                    data.assignments.remove(sentence.cellId);
                    sentence.cellId = "";
                    ctx.prison().write(data);
                }
                if (custody.enterJail(player, "prison")) {
                    deliverToCamp(player, camp, true);
                }
                return;
            }
        }
        var cell = blank(sentence.cellId) ? null : data.cell(sentence.cellId);
        var assignment = cell == null || blank(cell.id) ? null : data.assignments.get(cell.id);
        if (cell != null && !blank(cell.dimension) && assignment != null
                && sentence.id.equals(assignment.sentenceId)) {
            if (custody.enterJail(player, "prison")) {
                teleportToCell(player, cell);
            }
            return;
        }
        // The cell or its assignment is missing/mismatched: release any
        // dangling claim on this sentence and requeue exactly once.
        String sentenceId = sentence.id;
        releaseInvalidAssignments(data, sentence, player);
        sentence.status = "WAITING_CELL";
        sentence.cellId = "";
        repairWaitlist(data, player, sentence);
        if (data.waitlist.stream().noneMatch(e -> e != null && sentenceId.equals(e.sentenceId))) {
            var entry = new PrisonStore.WaitlistEntry();
            entry.sentenceId = sentence.id;
            entry.target = sentence.target;
            entry.targetUuid = sentence.targetUuid;
            entry.requestedAt = now();
            data.waitlist.add(entry);
        }
        ctx.prison().write(data);
        audit.record(AuditActions.PRISON_RECOVER, player.name(), uuidOf(player),
                sentence.target, sentence.targetUuid, AuditResults.SUCCESS,
                "missing_cell_assignment sentenceId=" + sentence.id);
    }

    /** Removes null/identity-less assignments and any that claim this sentence. */
    private boolean releaseInvalidAssignments(PrisonStore data, Sentence sentence,
                                              PlayerGateway player) {
        boolean changed = false;
        for (var cellId : new ArrayList<>(data.assignments.keySet())) {
            var a = data.assignments.get(cellId);
            boolean malformed = a == null
                    || (blank(a.sentenceId) && blank(a.targetUuid) && blank(a.target));
            boolean claims = a != null && sentence.id != null && sentence.id.equals(a.sentenceId)
                    || a != null && sentence.targetUuid != null && !sentence.targetUuid.isEmpty()
                            && sentence.targetUuid.equals(a.targetUuid);
            if (malformed || claims) {
                data.assignments.remove(cellId);
                changed = true;
                audit.record(AuditActions.PRISON_RECOVER, player.name(), uuidOf(player),
                        sentence.target, sentence.targetUuid, AuditResults.SUCCESS,
                        (malformed ? "malformed_assignment_released" : "assignment_released")
                                + " cellId=" + cellId);
            }
        }
        return changed;
    }

    /** Drops null or identity-less waitlist entries; returns true when it did. */
    private boolean repairWaitlist(PrisonStore data, PlayerGateway player, Sentence sentence) {
        boolean removed = data.waitlist.removeIf(e -> e == null
                || (blank(e.sentenceId) && blank(e.targetUuid) && blank(e.target)));
        if (removed) {
            audit.record(AuditActions.PRISON_RECOVER, player.name(), uuidOf(player),
                    sentence.target, sentence.targetUuid, AuditResults.SUCCESS,
                    "malformed_waitlist_discarded sentenceId=" + sentence.id);
        }
        return removed;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** True when the block/position is inside a configured cell (protection). */
    public boolean insideCell(String dimension, double x, double y, double z) {
        for (Cell c : store().cells) {
            if (c == null || c.dimension == null || !c.dimension.equals(dimension)) continue;
            if (x >= c.minX && x <= c.maxX && y >= c.minY && y <= c.maxY
                    && z >= c.minZ && z <= c.maxZ) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ tick

    /** Consumes sentence time; online+active only, elapsed capped at 5s/tick. */
    public void tick() {
        tickTampers(); // FUN-7: sub-second interruptibility — runs every tick
        if (now() - lastTickMs < 1000) return; // ~1s cadence, like the reference
        lastTickMs = now();
        var data = store();
        boolean changed = false;
        for (Sentence sentence : data.sentences) {
            if (sentence == null || !"ACTIVE".equals(sentence.status)
                    && !"WAITING_CELL".equals(sentence.status)) continue;
            var target = findFor(sentence);
            if ("WAITING_CELL".equals(sentence.status)) {
                // LAW-006: a camp-assigned prisoner must never be promoted
                // into a cell bunk — heal the stale waitlist state instead.
                var rr = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
                if (rr != null && rr.status == PrisonerStatus.IN_CAMP
                        && rr.assignedCampId != null && !rr.assignedCampId.isBlank()) {
                    sentence.status = "ACTIVE";
                    data.waitlist.removeIf(e -> e != null
                            && sentence.id != null && sentence.id.equals(e.sentenceId));
                    changed = true;
                } else {
                    var cell = firstFree(data);
                    if (cell == null) continue;
                    assignCell(data, sentence, cell);
                    data.waitlist.removeIf(e -> e != null
                            && sentence.id != null && sentence.id.equals(e.sentenceId));
                    changed = true;
                    if (target != null) {
                        if (custody.enterJail(target, "prison")) {
                            teleportToCell(target, cell);
                            target.setGameMode("adventure");
                        }
                    }
                }
            }
            if (target == null) continue; // offline time never counts
            var cell = data.cell(sentence.cellId);
            var rec = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
            if (rec != null && rec.status == PrisonerStatus.FUGITIVE) {
                // Custody breach: the sentence stays open but time does not
                // drain while the prisoner is at large — recapture resumes it.
                sentence.lastTickAt = now();
                changed = true;
                continue;
            }
            // LAW-006: camp prisoners enforce the camp perimeter instead of a
            // cell box — same breach semantics, a different box. ESCORTED is
            // included: a camp prisoner whose escorting officer vanished is
            // loose outside the wire, which is an escape, not a free pass.
            var camp = rec != null && rec.assignedCampId != null
                    && !rec.assignedCampId.isBlank() && camps != null
                    && (rec.status == PrisonerStatus.IN_CAMP
                            || rec.status == PrisonerStatus.ESCORTED)
                    ? camps.camp(rec.assignedCampId) : null;
            if (rec != null && rec.status == PrisonerStatus.IN_CAMP
                    && rec.assignedCampId != null && !rec.assignedCampId.isBlank()
                    && camp == null) {
                // The camp record vanished (store reset, hand edit): requeue
                // to the cell path instead of leaving a box-less custody
                // where every bounds check trivially passes.
                var reg = ctx.prisonerRegister().read();
                var rr = reg.prisoner(sentence.targetUuid);
                if (rr != null) {
                    rr.status = PrisonerStatus.IN_CELL;
                    rr.assignedCampId = "";
                    ctx.prisonerRegister().write(reg);
                }
                sentence.status = "WAITING_CELL";
                sentence.cellId = "";
                var entry = new PrisonStore.WaitlistEntry();
                entry.sentenceId = sentence.id;
                entry.target = sentence.target;
                entry.targetUuid = sentence.targetUuid;
                entry.requestedAt = now();
                data.waitlist.add(entry);
                changed = true;
                continue;
            }
            boolean insideBounds;
            if (camp != null && camp.boundary != null) {
                var b = camp.boundary;
                insideBounds = b.dimension().equals(target.dimension())
                        && target.x() >= b.minX() && target.x() <= b.maxX() + 1
                        && target.y() >= b.minY() && target.y() <= b.maxY() + 1
                        && target.z() >= b.minZ() && target.z() <= b.maxZ() + 1;
            } else if (cell != null) {
                insideBounds = cell.dimension.equals(target.dimension())
                        && target.x() >= cell.minX && target.x() <= cell.maxX
                        && target.y() >= cell.minY && target.y() <= cell.maxY
                        && target.z() >= cell.minZ && target.z() <= cell.maxZ;
            } else {
                insideBounds = true; // no box configured — nothing to breach
            }
            // The bounds check runs whether or not the canonical jail
            // transition can be re-asserted this tick — a prisoner under a
            // foreign custody state (roped, provider-owned) can still breach
            // the cell and must still flag FUGITIVE.
            boolean jailed = custody.enterJail(target, "prison");
            if (!insideBounds
                    && custody.escortOfficerWithin(target,
                            ctx.policies().escortTetherRadius) == null) {
                // M4: leaving custody bounds without a release — and without
                // a live escort beside them — is an escape: the prisoner
                // becomes a hunted fugitive, not a teleport back.
                // FUN-9: a prisoner blending under a stolen uniform gets a
                // grace window instead of the instant alarm — real guards
                // in radius still blow the cover on the detection sweep.
                if (escapes != null && escapes.suppressBreach(target, sentence)) continue;
                markFugitive(sentence, target);
                continue;
            }
            if (!jailed) continue;
            if (camp != null) {
                // The freedom price is a release valve independent of the
                // sentence clock: reaching it ends custody on the spot.
                long price = camps.freedomPrice(camp, rec);
                if (price > 0 && rec.laborAccount >= price) {
                    changed |= releaseSentence(data, sentence, target, "SERVED_LABOR", camp);
                    continue;
                }
            }
            long elapsed = Math.max(0, Math.min(now() - sentence.lastTickAt, 5000));
            sentence.lastTickAt = now();
            double x = target.x(), y = target.y(), z = target.z();
            boolean moved = sentence.lastX != null
                    && Math.abs(x - sentence.lastX) + Math.abs(y - sentence.lastY)
                            + Math.abs(z - sentence.lastZ) > 0.05;
            if (moved || sentence.lastX == null) {
                sentence.lastActivityAt = now();
                sentence.lastX = x;
                sentence.lastY = y;
                sentence.lastZ = z;
            }
            long grace = Math.max(10, ctx.policies().prisonAfkGraceSeconds) * 1000L;
            if (now() - sentence.lastActivityAt <= grace) {
                sentence.remainingActiveMs = Math.max(0, sentence.remainingActiveMs - elapsed);
            } else if (now() - sentence.lastActivityNoticeAt > grace) {
                sentence.lastActivityNoticeAt = now();
                target.tell("Timpul de detenție este pus pe pauză cât timp ești AFK. "
                        + "Mișcă-te sau interacționează periodic.");
            }
            changed = true;
            if (sentence.remainingActiveMs <= 0) {
                // Camp prisoners exit at the camp release point even when
                // the sentence clock, not the labor account, ends custody.
                changed |= releaseSentence(data, sentence, target, "SERVED", camp);
            }
        }
        changed |= pruneClosedSentences(data);
        if (changed) ctx.prison().write(data);
    }

    /**
     * Bounds the sentence store to {@code prisonRetentionLimit} terminal
     * sentences (SERVED/FORCED_RELEASE/CANCELLED), oldest first. Active and
     * waiting sentences are never pruned.
     */
    private boolean pruneClosedSentences(PrisonStore data) {
        int limit = ctx.policies().prisonRetentionLimit;
        if (limit <= 0) return false;
        List<Sentence> closed = new ArrayList<>();
        int open = 0;
        for (Sentence sentence : data.sentences) {
            if ("ACTIVE".equals(sentence.status) || "WAITING_CELL".equals(sentence.status)) {
                open++;
            } else {
                closed.add(sentence);
            }
        }
        int surplus = open + closed.size() - limit;
        if (surplus <= 0 || closed.isEmpty()) return false;
        closed.sort(Comparator.comparingLong(
                s -> s.servedAt != null ? s.servedAt : s.createdAt));
        return data.sentences.removeAll(closed.subList(0, Math.min(surplus, closed.size())));
    }

    /**
     * Custody breach without an official release: the register flips to
     * FUGITIVE, a system BOLO marks them hunted, and their real game mode is
     * restored (a fugitive fights back — the sentence pauses until recapture).
     */
    private void markFugitive(Sentence sentence, PlayerGateway target) {
        String uuid = sentence.targetUuid;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec != null && rec.status == PrisonerStatus.FUGITIVE) return;
        if (rec == null) {
            rec = new com.dwurdy.straja.domain.model.PrisonerRegisterRecord(
                    uuid, sentence.target, sentence.missionId);
            reg.put(rec);
        }
        rec.status = PrisonerStatus.FUGITIVE;
        rec.escapeCount++;
        rec.lastEscapeAt = now();
        ctx.prisonerRegister().write(reg);
        if (bolos != null) {
            bolos.markFugitive(uuid, sentence.target, "evadare din custodie", sentence.id);
        }
        if (target != null) {
            String prior = rec.priorGameMode == null || rec.priorGameMode.isBlank()
                    ? "survival" : rec.priorGameMode;
            target.setGameMode(prior);
            target.tell("Ai evadat din custodie. Ești căutat — gardienii te vor prinde din nou.");
        }
        audit.record(AuditActions.PRISON_FUGITIVE, "", "", sentence.target, uuid,
                AuditResults.SUCCESS, "escape sentenceId=" + sentence.id
                        + " escapes=" + rec.escapeCount);
    }

    /**
     * Uncuff boundary rule (M4): restraints removed inside custody keep the
     * prisoner IN_CELL; restraints removed outside custody mark them
     * fugitive. Wired from CustodyService's restraint-released hook.
     */
    public void onUncuffed(PlayerGateway officer, PlayerGateway target) {
        if (target == null) return;
        var data = store();
        var sentence = activeSentenceFrom(data, target);
        if (sentence == null) return;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(sentence.targetUuid);
        if (rec == null || rec.status.isReleased()) return;
        // LAW-006: camp custody uses the camp perimeter as its inside box —
        // an escorted prisoner (status ESCORTED, camp still assigned) uncuffed
        // inside the wire returns to IN_CAMP, not to freedom or a manhunt.
        if (rec.status != PrisonerStatus.IN_CELL && rec.status != PrisonerStatus.FUGITIVE
                && rec.assignedCampId != null && !rec.assignedCampId.isBlank()
                && camps != null) {
            var camp = camps.camp(rec.assignedCampId);
            if (camp == null) {
                // Stale link to a deleted camp — clear it and let the normal
                // cell-bounds logic below decide custody.
                rec.assignedCampId = "";
                ctx.prisonerRegister().write(reg);
            } else {
            var b = camp.boundary;
            if (b != null && b.dimension().equals(target.dimension())
                    && target.x() >= b.minX() && target.x() <= b.maxX() + 1
                    && target.y() >= b.minY() && target.y() <= b.maxY() + 1
                    && target.z() >= b.minZ() && target.z() <= b.maxZ() + 1) {
                if (rec.status != PrisonerStatus.IN_CAMP) {
                    rec.status = PrisonerStatus.IN_CAMP;
                    ctx.prisonerRegister().write(reg);
                }
                return; // uncuffed inside the camp — still camp custody
            }
            markFugitive(sentence, target);
            return;
            }
        }
        var cell = blank(sentence.cellId) ? null : data.cell(sentence.cellId);
        boolean inside = cell != null && cell.dimension.equals(target.dimension())
                && target.x() >= cell.minX && target.x() <= cell.maxX
                && target.y() >= cell.minY && target.y() <= cell.maxY
                && target.z() >= cell.minZ && target.z() <= cell.maxZ;
        if (inside) {
            if (rec.status != PrisonerStatus.IN_CELL) {
                rec.status = PrisonerStatus.IN_CELL;
                ctx.prisonerRegister().write(reg);
            }
            return;
        }
        markFugitive(sentence, target);
    }

    /**
     * Escort start (M4): a sentenced prisoner cuffed for escort is ESCORTED —
     * the cell bounds check stops counting them as escaped while the escort
     * holds. No-op for unsentenced suspects (ordinary cuffs).
     */
    public void onEscortStart(PlayerGateway officer, PlayerGateway target) {
        if (target == null) return;
        var data = store();
        var sentence = activeSentenceFrom(data, target);
        if (sentence == null) return;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(sentence.targetUuid);
        // Cell and camp prisoners are both escortable detainees.
        if (rec == null || (rec.status != PrisonerStatus.IN_CELL
                && rec.status != PrisonerStatus.IN_CAMP)) return;
        rec.status = PrisonerStatus.ESCORTED;
        ctx.prisonerRegister().write(reg);
        audit.record(AuditActions.PRISON_ESCORT, officer == null ? "system" : officer.name(),
                officer == null ? "" : uuidOf(officer), sentence.target,
                sentence.targetUuid, AuditResults.SUCCESS, "escort_start sentenceId=" + sentence.id);
    }

    // ------------------------------------------------------------ locker pool picks

    /** Arms the locker-pool pick: {@code locker} adds containers, {@code off} disarms. */
    public boolean setPickMode(PlayerGateway admin, String mode) {
        if (mode == null) return false;
        if ("off".equals(mode)) {
            pickModes.remove(admin.uuid());
            return true;
        }
        if (!"locker".equals(mode)) return false;
        pickModes.put(admin.uuid(), mode);
        return true;
    }

    /** `/straja prison lockers` — the pool plus each chest's current owner. */
    public void listLockers(PlayerGateway admin) {
        var data = store();
        if (data.lockerPool == null || data.lockerPool.isEmpty()) {
            admin.refuse("straja.prison.no_lockers", "straja.remedy.pick_locker");
            return;
        }
        var reg = ctx.prisonerRegister().read();
        for (var point : data.lockerPool) {
            if (point == null) continue;
            String canon = ctx.containers().canonicalKey(
                    point.dimension(), point.x(), point.y(), point.z());
            String owner = "liber";
            for (var rec : reg.prisoners().values()) {
                if (rec != null && rec.personalLocker != null
                        && rec.personalLocker.stream().anyMatch(p -> p != null
                                && p.key().equals(point.key()))) {
                    owner = rec.detaineeName;
                    break;
                }
            }
            admin.tell(canon + " — " + owner);
        }
    }

    /**
     * Right-click consumed by an armed locker pick; returns true when the
     * click was consumed (caller cancels the interaction).
     */
    public boolean onPickClick(PlayerGateway admin, String dimension, int x, int y, int z) {
        if (!"locker".equals(pickModes.get(admin.uuid()))) return false;
        if (!ctx.containers().isContainer(dimension, x, y, z)) {
            admin.refuse("straja.storage.pick_not_container", "straja.remedy.fix_retry");
            return true;
        }
        var data = store();
        String canon = ctx.containers().canonicalKey(dimension, x, y, z);
        boolean dup = data.lockerPool.stream().anyMatch(p -> p != null
                && ctx.containers().canonicalKey(p.dimension(), p.x(), p.y(), p.z()).equals(canon));
        boolean evidence = ctx.lawCheckpoints().read().checkpoints().values().stream()
                .filter(site -> site != null && site.evidenceChests != null)
                .flatMap(site -> site.evidenceChests.stream())
                .filter(pt -> pt != null)
                .anyMatch(pt -> ctx.containers()
                        .canonicalKey(pt.dimension(), pt.x(), pt.y(), pt.z()).equals(canon));
        var point = new com.dwurdy.straja.domain.model.StoragePoint(dimension, x, y, z);
        if (dup) {
            admin.tellKey("straja.storage.pick_chest_dup", canon);
        } else if (evidence) {
            // Evidence chests are not personal lockers — overlap would
            // release seized contraband back into a prisoner's hands.
            admin.refuse("straja.prison.pick_in_evidence", "straja.remedy.fix_retry");
        } else {
            data.lockerPool.add(point);
            ctx.prison().write(data);
            admin.tellKey("straja.prison.pick_locker", canon, data.lockerPool.size());
        }
        return true;
    }

    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }
}
