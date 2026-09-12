package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.ArchiveStore;
import com.dwurdy.straja.domain.model.AuditEntry;
import com.dwurdy.straja.domain.model.ComplaintStore;
import com.dwurdy.straja.domain.model.CustodyStore;
import com.dwurdy.straja.domain.model.FineStore;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Mission;
import com.dwurdy.straja.domain.model.MissionStore;
import com.dwurdy.straja.domain.model.PrisonStore;
import com.dwurdy.straja.domain.model.RoomStore;
import com.dwurdy.straja.domain.model.SetupData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Migrates persistent state from the legacy KubeJS implementation. Input is a
 * flat {@code key -> raw JSON string} map (as stored in
 * {@code kubejs_persistent_data.nbt}) plus per-player maps keyed by player UUID
 * (from {@code playerdata/*.dat → KubeJSPersistentData}). Field names in the
 * domain stores deliberately mirror the KubeJS schema, so migration is a
 * deserialize → merge → write through the normal repositories.
 *
 * <p>Idempotent: re-running merges by id and never duplicates records; each
 * run appends an audit entry with per-store counts.
 */
public final class MigrationService {
    private static final Gson GSON = new Gson();

    private final StrajaContext ctx;
    private final AuditService audit;

    public MigrationService(StrajaContext ctx, AuditService audit) {
        this.ctx = ctx;
        this.audit = audit;
    }

    public record Report(List<String> lines, int errors) {
        public boolean ok() { return errors == 0; }
    }

    private interface Store<T> { T read(); void write(T store); }

    /** Migrates all server-level KubeJS keys found in {@code data}. */
    public Report migrateServer(Map<String, String> data) {
        List<String> lines = new ArrayList<>();
        int errors = 0;

        errors += migrateSetup(data, lines);
        errors += migrateStore(data, "straja_fines", FineStore.class, lines, "fines",
                (in, out) -> dedupe(in.fines, out.fines, f -> f.id)
                        + dedupe(in.tasks, out.tasks, t -> t.id),
                new Store<FineStore>() {
                    public FineStore read() { return ctx.fines().read(); }
                    public void write(FineStore s) { ctx.fines().write(s); }
                });
        errors += migrateStore(data, "straja_prisons", PrisonStore.class, lines, "prisons",
                (in, out) -> dedupe(in.sentences, out.sentences, s -> s.id)
                        + dedupe(in.cells, out.cells, c -> c.id),
                new Store<PrisonStore>() {
                    public PrisonStore read() { return ctx.prison().read(); }
                    public void write(PrisonStore s) { ctx.prison().write(s); }
                });
        errors += migrateStore(data, "straja_rooms", RoomStore.class, lines, "rooms",
                (in, out) -> dedupe(in.rooms, out.rooms, r -> r.id),
                new Store<RoomStore>() {
                    public RoomStore read() { return ctx.rooms().read(); }
                    public void write(RoomStore s) { ctx.rooms().write(s); }
                });
        errors += migrateStore(data, "straja_complaints", ComplaintStore.class, lines, "complaints",
                (in, out) -> dedupe(in.complaints, out.complaints, c -> c.id),
                new Store<ComplaintStore>() {
                    public ComplaintStore read() { return ctx.complaints().read(); }
                    public void write(ComplaintStore s) { ctx.complaints().write(s); }
                });
        errors += migrateStore(data, "straja_archive", ArchiveStore.class, lines, "archive",
                (in, out) -> mapMerge(in.sheets, out.sheets)
                        + mapMerge(in.folders, out.folders),
                new Store<ArchiveStore>() {
                    public ArchiveStore read() { return ctx.archive().read(); }
                    public void write(ArchiveStore s) { ctx.archive().write(s); }
                });
        errors += migrateMissions(data, lines);
        errors += migrateCustody(data, lines);
        errors += migrateAudit(data, lines);
        migrateCommissionerHint(data, lines);

        audit.record("kubejs_migration", "migration", null, null, null,
                errors == 0 ? "SUCCESS" : "PARTIAL", String.join(" | ", lines));
        return new Report(lines, errors);
    }

    /** Migrates one player's {@code KubeJSPersistentData} string map. */
    public Report migratePlayer(UUID uuid, Map<String, String> data) {
        List<String> lines = new ArrayList<>();
        int errors = 0;
        String state = find(data, "straja_state");
        if (state != null) {
            try {
                GuardState parsed = GSON.fromJson(state, GuardState.class);
                if (parsed != null) {
                    ctx.players().write(uuid, parsed);
                    lines.add("player " + uuid + " state imported rank=" + parsed.rank);
                }
            } catch (Exception e) {
                errors++;
                lines.add("player " + uuid + " straja_state FAILED: " + e.getMessage());
            }
        }
        String fineDraft = find(data, "straja_fine_draft");
        if (fineDraft != null && !fineDraft.isBlank()) {
            try {
                FineStore store = ctx.fines().read();
                FineStore.FineDraft draft = GSON.fromJson(fineDraft, FineStore.FineDraft.class);
                if (draft != null && draft.target != null && !draft.target.isBlank()) {
                    store.drafts.put(uuid.toString(), draft);
                    ctx.fines().write(store);
                    lines.add("player " + uuid + " fine draft imported");
                }
            } catch (Exception e) {
                errors++;
                lines.add("player " + uuid + " fine draft FAILED: " + e.getMessage());
            }
        }
        String archivist = find(data, "straja_archivist");
        if ("1".equals(archivist)) {
            ArchiveStore store = ctx.archive().read();
            if (!store.archivists.containsKey(uuid.toString())) {
                store.archivists.put(uuid.toString(), "");
                ctx.archive().write(store);
                lines.add("player " + uuid + " archivist flag imported");
            }
        }
        return new Report(lines, errors);
    }

    /** KubeJS nests player keys inside a {@code KubeJSPersistentData} compound. */
    private static String find(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) value = data.get("KubeJSPersistentData." + key);
        return value;
    }

    // ------------------------------------------------------------ server stores

    private int migrateSetup(Map<String, String> data, List<String> lines) {
        String raw = data.get("straja_setup");
        if (raw == null) return 0;
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            SetupData setup = ctx.setup().read();
            int imported = 0;
            if (json.has("checkpoints") && json.get("checkpoints").isJsonObject()) {
                for (var e : json.getAsJsonObject("checkpoints").entrySet()) {
                    var cp = GSON.fromJson(e.getValue(), SetupData.Checkpoint.class);
                    if (cp == null) continue;
                    if (cp.id == null || cp.id.isBlank()) cp.id = e.getKey();
                    var existing = setup.checkpoints.stream()
                            .filter(c -> c.id != null && c.id.equalsIgnoreCase(cp.id))
                            .findFirst().orElse(null);
                    if (existing == null) {
                        setup.checkpoints.add(cp);
                        imported++;
                    } else if (cp.x != null) {
                        existing.x = cp.x; existing.y = cp.y; existing.z = cp.z;
                        existing.dimension = cp.dimension;
                    }
                }
            }
            if (json.has("locations") && json.get("locations").isJsonObject()) {
                for (var e : json.getAsJsonObject("locations").entrySet()) {
                    var loc = GSON.fromJson(e.getValue(), SetupData.Location.class);
                    if (loc != null) setup.locations.put(e.getKey(), loc);
                }
            }
            if (json.has("missionMinutes") && json.get("missionMinutes").isJsonObject()) {
                for (var e : json.getAsJsonObject("missionMinutes").entrySet()) {
                    setup.missionMinutes.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            ctx.setup().write(setup);
            lines.add("setup: " + imported + " checkpoints + locations imported");
            return 0;
        } catch (Exception e) {
            lines.add("setup FAILED: " + e.getMessage());
            return 1;
        }
    }

    private int migrateMissions(Map<String, String> data, List<String> lines) {
        String raw = data.get("straja_missions");
        if (raw == null) return 0;
        try {
            MissionStore store = ctx.missions().read();
            // KubeJS stores missions as a bare JSON array.
            Mission[] parsed = GSON.fromJson(raw, Mission[].class);
            int imported = 0;
            int maxNumericId = store.nextId;
            for (Mission m : parsed == null ? new Mission[0] : parsed) {
                if (m.id != null && store.missions.stream().noneMatch(x -> m.id.equals(x.id))) {
                    store.missions.add(m);
                    imported++;
                    try { maxNumericId = Math.max(maxNumericId, Integer.parseInt(m.id) + 1); }
                    catch (NumberFormatException ignored) {}
                }
            }
            store.nextId = maxNumericId;
            String drafts = data.get("straja_mission_drafts");
            if (drafts != null) {
                Map<?, ?> map = GSON.fromJson(drafts, Map.class);
                for (var e : map.entrySet()) {
                    var draft = GSON.fromJson(GSON.toJsonTree(e.getValue()),
                            com.dwurdy.straja.domain.model.MissionDraft.class);
                    if (draft != null) store.drafts.putIfAbsent(String.valueOf(e.getKey()), draft);
                }
            }
            String budgets = data.get("straja_mission_reward_budgets");
            if (budgets != null) {
                Map<?, ?> map = GSON.fromJson(budgets, Map.class);
                for (var e : map.entrySet()) {
                    if (e.getValue() instanceof Number n) {
                        store.rewardBudgets.put(String.valueOf(e.getKey()), n.intValue());
                    }
                }
            }
            ctx.missions().write(store);
            lines.add("missions: " + imported + " imported");
            return 0;
        } catch (Exception e) {
            lines.add("missions FAILED: " + e.getMessage());
            return 1;
        }
    }

    private int migrateCustody(Map<String, String> data, List<String> lines) {
        String cuffed = data.get("straja_cuffed_players");
        String bound = data.get("straja_bound_players");
        String sacks = data.get("straja_head_sack_players");
        String downed = data.get("straja_downed_players");
        String requests = data.get("straja_cuff_requests");
        String pendingKeys = data.get("straja_cuff_pending_keys");
        if (cuffed == null && bound == null && sacks == null && downed == null
                && requests == null && pendingKeys == null) return 0;
        try {
            CustodyStore store = ctx.custody().read();
            int imported = 0;
            if (cuffed != null) imported += mapMerge(
                    jsonMap(cuffed, CustodyStore.CuffRecord.class), store.cuffed);
            if (bound != null) imported += mapMerge(
                    jsonMap(bound, CustodyStore.BoundRecord.class), store.bound);
            if (sacks != null) imported += mapMerge(
                    jsonMap(sacks, CustodyStore.HeadSackRecord.class), store.headSacks);
            if (downed != null) imported += mapMerge(
                    jsonMap(downed, CustodyStore.DownedRecord.class), store.downed);
            if (requests != null) imported += mapMerge(
                    jsonMap(requests, CustodyStore.CuffRequest.class), store.cuffRequests);
            if (pendingKeys != null) {
                Map<?, ?> map = GSON.fromJson(pendingKeys, Map.class);
                for (var e : map.entrySet()) {
                    if (e.getValue() instanceof Number n) {
                        store.pendingKeys.put(String.valueOf(e.getKey()), n.intValue());
                    }
                }
            }
            ctx.custody().write(store);
            lines.add("custody: " + imported + " records imported");
            return 0;
        } catch (Exception e) {
            lines.add("custody FAILED: " + e.getMessage());
            return 1;
        }
    }

    private int migrateAudit(Map<String, String> data, List<String> lines) {
        String raw = data.get("straja_audit");
        if (raw == null) return 0;
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            if (!json.has("entries") || !json.get("entries").isJsonArray()) return 0;
            var existing = ctx.audit().entries();
            int imported = 0;
            for (var el : json.getAsJsonArray("entries")) {
                if (!el.isJsonObject()) continue;
                JsonObject e = el.getAsJsonObject();
                // KubeJS audit: {id, timestamp, actorUuid, actorName, actorTier, action,
                //                targetUuid, targetName, oldLifecycle, newLifecycle,
                //                result, reason, correlationId, details}
                AuditEntry entry = new AuditEntry();
                entry.at = e.has("timestamp") ? e.get("timestamp").getAsLong() : 0;
                entry.action = str(e, "action");
                entry.actor = str(e, "actorName");
                entry.actorUuid = str(e, "actorUuid");
                entry.target = str(e, "targetName");
                entry.targetUuid = str(e, "targetUuid");
                entry.result = str(e, "result");
                entry.reason = str(e, "reason");
                entry.details = "kubejs id=" + str(e, "id") + " tier=" + str(e, "actorTier")
                        + (e.has("details") && !e.get("details").isJsonNull()
                                ? " " + e.get("details") : "");
                boolean dup = existing.stream().anyMatch(x -> x.at == entry.at
                        && x.action.equals(entry.action) && x.actorUuid.equals(entry.actorUuid)
                        && x.details.contains(str(e, "id")));
                if (!dup) { ctx.audit().append(entry); imported++; }
            }
            lines.add("audit: " + imported + " entries imported");
            return 0;
        } catch (Exception e) {
            lines.add("audit FAILED: " + e.getMessage());
            return 1;
        }
    }

    /** The KubeJS debug commissioner override is a config value — report it only. */
    private void migrateCommissionerHint(Map<String, String> data, List<String> lines) {
        String uuid = data.get("straja_debug_commissioner_uuid");
        if (uuid != null && !uuid.isBlank()) {
            lines.add("commissioner override found: " + uuid.trim()
                    + " — set testing.commissionerUuid/straja.commissionerUuid in config");
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // ------------------------------------------------------------ merge helpers

    private <T> int migrateStore(Map<String, String> data, String key, Class<T> type,
                                 List<String> lines, String label,
                                 BiFunction<T, T, Integer> merger, Store<T> repo) {
        String raw = data.get(key);
        if (raw == null) return 0;
        try {
            T incoming = GSON.fromJson(raw, type);
            T target = repo.read();
            int imported = incoming == null ? 0 : merger.apply(incoming, target);
            if (incoming != null) mergeScalars(incoming, target);
            repo.write(target);
            lines.add(label + ": " + imported + " imported");
            return 0;
        } catch (Exception e) {
            lines.add(label + " FAILED: " + e.getMessage());
            return 1;
        }
    }

    /** Copies scalars/maps with no per-record id (nextId, budgets, abuse, selections). */
    private void mergeScalars(Object incoming, Object target) {
        if (incoming instanceof FineStore in && target instanceof FineStore out) {
            out.nextId = Math.max(out.nextId, in.nextId);
            in.drafts.forEach(out.drafts::putIfAbsent);
            in.appealAbuse.forEach(out.appealAbuse::putIfAbsent);
        } else if (incoming instanceof ComplaintStore in && target instanceof ComplaintStore out) {
            out.nextId = Math.max(out.nextId, in.nextId);
            out.rewardBudgets.putAll(in.rewardBudgets);
        } else if (incoming instanceof PrisonStore in && target instanceof PrisonStore out) {
            in.assignments.forEach(out.assignments::putIfAbsent);
            in.selections.forEach(out.selections::putIfAbsent);
            in.waitlist.stream().filter(w -> out.waitlist.stream()
                    .noneMatch(x -> x.sentenceId.equals(w.sentenceId))).forEach(out.waitlist::add);
        } else if (incoming instanceof RoomStore in && target instanceof RoomStore out) {
            in.assignments.forEach(out.assignments::putIfAbsent);
            in.selections.forEach(out.selections::putIfAbsent);
            in.waitlist.stream().filter(w -> out.waitlist.stream()
                    .noneMatch(x -> x.playerUuid.equals(w.playerUuid))).forEach(out.waitlist::add);
        } else if (incoming instanceof ArchiveStore in && target instanceof ArchiveStore out) {
            in.catalog.forEach(out.catalog::putIfAbsent);
            in.archivists.forEach(out.archivists::putIfAbsent);
            dedupe(in.copies, out.copies, c -> c.id);
            dedupe(in.operations, out.operations, o -> o.id);
            dedupe(in.pendingDeliveries, out.pendingDeliveries, d -> d.id);
            out.nextFolderNumber = Math.max(out.nextFolderNumber, in.nextFolderNumber);
            out.nextSheetNumber = Math.max(out.nextSheetNumber, in.nextSheetNumber);
            out.nextCatalogNumber = Math.max(out.nextCatalogNumber, in.nextCatalogNumber);
            out.nextCopyNumber = Math.max(out.nextCopyNumber, in.nextCopyNumber);
        }
    }

    private <T> int dedupe(List<T> incoming, List<T> target, Function<T, String> id) {
        int added = 0;
        for (T item : incoming) {
            String key = id.apply(item);
            if (key == null) continue;
            if (target.stream().noneMatch(x -> key.equals(id.apply(x)))) {
                target.add(item);
                added++;
            }
        }
        return added;
    }

    private <T> int mapMerge(Map<String, T> incoming, Map<String, T> target) {
        int added = 0;
        for (var e : incoming.entrySet()) {
            if (!target.containsKey(e.getKey())) { target.put(e.getKey(), e.getValue()); added++; }
        }
        return added;
    }

    private <T> Map<String, T> jsonMap(String raw, Class<T> type) {
        Map<?, ?> map = GSON.fromJson(raw, Map.class);
        Map<String, T> out = new java.util.LinkedHashMap<>();
        if (map == null) return out;
        for (var e : map.entrySet()) {
            T value = GSON.fromJson(GSON.toJsonTree(e.getValue()), type);
            if (value != null) out.put(String.valueOf(e.getKey()), value);
        }
        return out;
    }
}
