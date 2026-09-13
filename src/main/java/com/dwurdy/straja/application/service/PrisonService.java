package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.application.port.in.PrisonRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.Cell;
import com.dwurdy.straja.domain.model.PrisonStore;
import com.dwurdy.straja.domain.model.Sentence;
import com.dwurdy.straja.domain.model.SetupData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Prison: bounded cells, sentences with online-active-only time, waitlist,
 * release points and cell protection. Ported from the reference civic service.
 */
public class PrisonService implements PrisonRoleplayUseCase {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final CustodyRoleplayUseCase custody;
    private long lastTickMs;

    public PrisonService(StrajaContext ctx, PlayerService players, AuditService audit,
                         CustodyRoleplayUseCase custody) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.custody = custody;
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
            actor.tell("Doar Comisaru' poate configura celule.");
            return false;
        }
        var data = store();
        String id = (requestedId == null || requestedId.isBlank()
                ? nextCellId(data) : requestedId).toLowerCase();
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            actor.tell("ID de celulă invalid.");
            return false;
        }
        if (data.assignments.containsKey(id)) {
            actor.tell("Celula este ocupată și nu poate fi recreată până la eliberare.");
            return false;
        }
        if (data.cells.size() >= ctx.policies().prisonMaxCells && data.cell(id) == null) {
            actor.tell("Numărul maxim de celule a fost atins.");
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
        var geometryError = validateCellGeometry(cell);
        if (geometryError != null) {
            actor.tell(geometryError);
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
            return "Interiorul celulei este prea mic pentru o celulă sigură.";
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
            return "Celula depășește limitele sigure de dimensiune sau volum.";
        }
        List<int[]> doors = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (!boundary) continue;
                    var block = ctx.world().blockAt(cell.dimension, x, y, z);
                    if (block == null) return "Un bloc din zona celulei nu poate fi citit; celula nu a fost creată.";
                    if (block.solid()) continue;
                    if (block.door()) {
                        doors.add(new int[]{x, y, z});
                        continue;
                    }
                    return "Pereții celulei nu sunt închiși complet.";
                }
            }
        }
        if (ctx.policies().roomRequireSingleDoor) {
            if (doors.size() != 2 || doors.get(0)[0] != doors.get(1)[0]
                    || doors.get(0)[2] != doors.get(1)[2]
                    || Math.abs(doors.get(0)[1] - doors.get(1)[1]) != 1) {
                return "Celula trebuie să aibă exact o ușă standard de două blocuri.";
            }
        } else if (doors.isEmpty()) {
            return "Celula trebuie să aibă o ușă.";
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
            actor.tell("Nu există celule configurate.");
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
            if (target != null) {
                teleportToCell(target, cell);
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
        if (!ctx.policies().prisonEnabled) {
            if (actor != null) actor.tell("Sistemul de detenție este dezactivat.");
            audit.record("prison_arrest", actor == null ? "" : actor.name(),
                    actor == null ? "" : uuidOf(actor), target.name(), uuidOf(target),
                    "REFUSED", "prison_disabled");
            return null;
        }
        var data = store();
        var existing = activeSentence(target);
        if (existing != null) return existing;
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
        var online = findFor(sentence);
        if (online != null && !sentence.cellId.isEmpty()) {
            custody.resolveDowned(online, "PRISON");
            teleportToCell(online, cell);
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

    private void teleportToCell(PlayerGateway player, Cell cell) {
        player.teleport(cell.dimension, cell.minX + 1 + 0.5, cell.minY + 1, cell.minZ + 1 + 0.5);
    }

    public boolean release(PlayerGateway actor, PlayerGateway target, String reason) {
        if (target == null) {
            actor.tell("Jucătorul țintă trebuie să fie online.");
            return false;
        }
        if (!players.hasCapability(actor, Capability.EXECUTE_ARRESTS)) {
            actor.tell("Doar un Străjer sau Comisaru' poate elibera un deținut.");
            audit.record("prison_release", actor.name(), uuidOf(actor),
                    target.name(), uuidOf(target),
                    "REFUSED", "missing_authority");
            return false;
        }
        var data = store();
        var sentence = activeSentenceFrom(data, target);
        if (sentence == null) {
            actor.tell(target.name() + " nu are o sentință activă.");
            return false;
        }
        releaseSentence(data, sentence, target, "FORCED_RELEASE");
        ctx.prison().write(data);
        audit.record("prison_release", actor.name(), uuidOf(actor),
                sentence.target, sentence.targetUuid, "SUCCESS",
                "FORCED_RELEASE reason=" + (reason == null ? "" : reason));
        return true;
    }

    private void releaseSentence(PrisonStore data, Sentence sentence, PlayerGateway target, String reason) {
        if (sentence == null || java.util.Set.of("SERVED", "FORCED_RELEASE", "CANCELLED")
                .contains(sentence.status)) return;
        sentence.status = "FORCED_RELEASE".equals(reason) ? "FORCED_RELEASE" : "SERVED";
        sentence.servedAt = now();
        sentence.releaseReason = reason == null ? "SERVED" : reason;
        if (!sentence.cellId.isEmpty()) data.assignments.remove(sentence.cellId);
        if (target != null) {
            teleportToRelease(target);
            target.tell("FORCED_RELEASE".equals(sentence.status)
                    ? "Ai fost eliberat administrativ din celulă."
                    : "Ți-ai executat sentința. Ești eliberat din celulă.");
        }
    }

    private void teleportToRelease(PlayerGateway player) {
        SetupData.Location point = ctx.setup().read().location("prisonRelease");
        if (point != null) player.teleport(point.dimension, point.x + 0.5, point.y, point.z + 0.5);
    }

    public void status(PlayerGateway player) {
        var sentence = activeSentence(player);
        if (sentence == null) {
            player.tell("Nu ai o sentință activă.");
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
        var cell = blank(sentence.cellId) ? null : data.cell(sentence.cellId);
        var assignment = cell == null || blank(cell.id) ? null : data.assignments.get(cell.id);
        if (cell != null && !blank(cell.dimension) && assignment != null
                && sentence.id.equals(assignment.sentenceId)) {
            custody.resolveDowned(player, "PRISON");
            teleportToCell(player, cell);
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
                    custody.resolveDowned(target, "PRISON");
                    teleportToCell(target, cell);
                }
            }
            if (target == null) continue; // offline time never counts
            var cell = data.cell(sentence.cellId);
            if (cell != null && !(cell.dimension.equals(target.dimension())
                    && target.x() >= cell.minX && target.x() <= cell.maxX
                    && target.y() >= cell.minY && target.y() <= cell.maxY
                    && target.z() >= cell.minZ && target.z() <= cell.maxZ)) {
                teleportToCell(target, cell);
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
            if (sentence.remainingActiveMs <= 0) releaseSentence(data, sentence, target, "SERVED");
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

    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }
}
