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
    private long lastTickMs;
    /** Armed admin picks for the locker pool (admin uuid → mode). */
    private final java.util.Map<java.util.UUID, String> pickModes = new java.util.HashMap<>();

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
            audit.record("prison_cell_create", actor.name(), uuidOf(actor), id, "", "REFUSED", "invalid_geometry");
            return false;
        }
        cell.createdAt = now();
        data.cells.removeIf(c -> c.id.equalsIgnoreCase(id));
        data.cells.add(cell);
        ctx.prison().write(data);
        actor.tell("Celula " + id + " a fost creată.");
        audit.record("prison_cell_create", actor.name(), uuidOf(actor),
                "", "", "SUCCESS", "cellId=" + id);
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
            Cell cell = firstFree(data);
            if (sentence == null || !"WAITING_CELL".equals(sentence.status) || cell == null) continue;
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
            audit.record("prison_arrest", actor == null ? "" : actor.name(),
                    actor == null ? "" : uuidOf(actor), target.name(), uuidOf(target),
                    "REFUSED", "prison_disabled");
            return null;
        }
        var data = store();
        var existing = activeSentence(target);
        if (existing != null) {
            recapture(target, existing);
            return existing;
        }
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
        if (cell != null) {
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
        if (online != null && seizure != null) {
            // Custody consumes the person AND the inventory: seize at the
            // arrest position before the handover moves the body into a cell.
            seizure.onArrest(online, siteFor(siteId, missionId),
                    blank(reason) ? missionId : reason);
        }
        if (online != null && !sentence.cellId.isEmpty()) {
            if (custody.enterJail(online, "prison")) {
                teleportToCell(online, cell);
                online.setGameMode("adventure");
            } else {
                online.refuse("straja.prison.handover_retry", "straja.remedy.jailer");
            }
        }
        if (online != null) {
            online.tell("Ai fost arestat pentru " + sentence.sentenceDays
                    + " zi(e) de Minecraft. Timpul se consumă doar când ești online și activ.");
        }
        audit.record("prison_arrest", actor == null ? "" : actor.name(),
                actor == null ? "" : uuidOf(actor), sentence.target, sentence.targetUuid,
                "SUCCESS", "sentenceId=" + sentence.id + " days=" + sentenceDays);
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
        if (rec != null && rec.status != PrisonerStatus.RELEASED) {
            rec.status = PrisonerStatus.IN_CELL;
            ctx.prisonerRegister().write(reg);
        }
        if (wasHunted && bolos != null && target.uuid() != null) {
            bolos.clearFor(target.uuid());
        }
        var cell = blank(sentence.cellId) ? null : store().cell(sentence.cellId);
        if (cell != null && custody.enterJail(target, "prison")) {
            teleportToCell(target, cell);
            target.setGameMode("adventure");
        }
        if (wasHunted) {
            target.tell("Ai fost readus în custodie.");
            audit.record("prison_recapture", "", "", sentence.target, uuid,
                    "SUCCESS", "sentenceId=" + sentence.id);
        }
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
        if (!players.hasCapability(actor, Capability.EXECUTE_ARRESTS)) {
            actor.refuse("straja.prison.release_rank", "straja.remedy.jailer");
            audit.record("prison_release", actor.name(), uuidOf(actor),
                    target.name(), uuidOf(target),
                    "REFUSED", "missing_authority");
            return false;
        }
        var data = store();
        var sentence = activeSentenceFrom(data, target);
        if (sentence == null) {
            actor.refuse("straja.prison.no_sentence", "straja.remedy.jailer", target.name());
            return false;
        }
        if (!releaseSentence(data, sentence, target, "FORCED_RELEASE")) return false;
        ctx.prison().write(data);
        audit.record("prison_release", actor.name(), uuidOf(actor),
                sentence.target, sentence.targetUuid, "SUCCESS",
                "FORCED_RELEASE reason=" + (reason == null ? "" : reason));
        return true;
    }

    private boolean releaseSentence(PrisonStore data, Sentence sentence, PlayerGateway target, String reason) {
        if (sentence == null || java.util.Set.of("SERVED", "FORCED_RELEASE", "CANCELLED")
                .contains(sentence.status)) return false;
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
        if (rec != null && rec.status != PrisonerStatus.RELEASED) {
            rec.status = PrisonerStatus.RELEASED;
            rec.releasedAt = now();
            if (seizure != null) seizure.releaseLocker(reg, target, rec);
            ctx.prisonerRegister().write(reg);
        }
        if (bolos != null && sentence.targetUuid != null && !sentence.targetUuid.isEmpty()) {
            try {
                bolos.clearFor(java.util.UUID.fromString(sentence.targetUuid));
            } catch (IllegalArgumentException ignored) {}
        }
        if (target != null) {
            target.setGameMode(rec == null || blank(rec.priorGameMode)
                    ? "survival" : rec.priorGameMode);
            teleportToRelease(target);
            target.tell("FORCED_RELEASE".equals(sentence.status)
                    ? "Ai fost eliberat administrativ din celulă."
                    : "Ți-ai executat sentința. Ești eliberat din celulă.");
        }
        return true;
    }

    private void teleportToRelease(PlayerGateway player) {
        SetupData.Location point = ctx.setup().read().location("prisonRelease");
        if (point != null) player.teleport(point.dimension, point.x + 0.5, point.y, point.z + 0.5);
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
            boolean adopted = register.prisonerByName(name) != null;
            if (adopted) register.adoptByName(name, uuid);
            boolean lockersMoved = register.rekeyPendingLockers(legacyId, uuid);
            if (adopted || lockersMoved) ctx.prisonerRegister().write(register);
        }
        // Released-while-offline prisoners collect locker belongings at login.
        if (seizure != null) seizure.deliverPendingLockers(player);
        var data = store();
        var sentence = activeSentenceFrom(data, player);
        if (sentence == null || !"WAITING_CELL".equals(sentence.status)
                && !"ACTIVE".equals(sentence.status)) return;
        if (sentence.id == null || sentence.id.isBlank()
                || (blank(sentence.targetUuid) && blank(sentence.target))) {
            // An active sentence without verifiable identity must not drive
            // teleports or assignments: cancel it and release anything claimed.
            sentence.status = "CANCELLED";
            releaseInvalidAssignments(data, sentence, player);
            ctx.prison().write(data);
            audit.record("prison_recover", player.name(), uuidOf(player),
                    sentence.target, sentence.targetUuid, "SUCCESS",
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
        var regRecord = ctx.prisonerRegister().read().prisoner(sentence.targetUuid);
        if (regRecord != null && regRecord.status == PrisonerStatus.FUGITIVE) return;
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
        audit.record("prison_recover", player.name(), uuidOf(player),
                sentence.target, sentence.targetUuid, "SUCCESS",
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
                audit.record("prison_recover", player.name(), uuidOf(player),
                        sentence.target, sentence.targetUuid, "SUCCESS",
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
            audit.record("prison_recover", player.name(), uuidOf(player),
                    sentence.target, sentence.targetUuid, "SUCCESS",
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
        if (now() - lastTickMs < 1000) return; // ~1s cadence, like the reference
        lastTickMs = now();
        var data = store();
        boolean changed = false;
        for (Sentence sentence : data.sentences) {
            if (sentence == null || !"ACTIVE".equals(sentence.status)
                    && !"WAITING_CELL".equals(sentence.status)) continue;
            var target = findFor(sentence);
            if ("WAITING_CELL".equals(sentence.status)) {
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
            if (!custody.enterJail(target, "prison")) continue;
            if (cell != null && !(cell.dimension.equals(target.dimension())
                    && target.x() >= cell.minX && target.x() <= cell.maxX
                    && target.y() >= cell.minY && target.y() <= cell.maxY
                    && target.z() >= cell.minZ && target.z() <= cell.maxZ)
                    && !custody.isCuffed(target)) {
                // M4: leaving custody bounds without a release is an escape —
                // the prisoner becomes a hunted fugitive, not a teleport back.
                markFugitive(sentence, target);
                continue;
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
                changed |= releaseSentence(data, sentence, target, "SERVED");
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
        audit.record("prison_fugitive", "", "", sentence.target, uuid,
                "SUCCESS", "escape sentenceId=" + sentence.id
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
        if (rec == null || rec.status == PrisonerStatus.RELEASED) return;
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
        if (rec == null || rec.status != PrisonerStatus.IN_CELL) return;
        rec.status = PrisonerStatus.ESCORTED;
        ctx.prisonerRegister().write(reg);
        audit.record("prison_escort", officer == null ? "system" : officer.name(),
                officer == null ? "" : uuidOf(officer), sentence.target,
                sentence.targetUuid, "SUCCESS", "escort_start sentenceId=" + sentence.id);
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
        var point = new com.dwurdy.straja.domain.model.StoragePoint(dimension, x, y, z);
        if (dup) {
            admin.tellKey("straja.storage.pick_chest_dup", canon);
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
