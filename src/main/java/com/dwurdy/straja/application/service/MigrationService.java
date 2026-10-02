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
        errors += migrateLawEnforcement(data, lines);
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
        errors += migratePlayerLawFlags(uuid, data, lines);
        return new Report(lines, errors);
    }

    /** LAW-001: thief ledger + custody flags from the legacy player NBT keys. */
    private int migratePlayerLawFlags(UUID uuid, Map<String, String> data, List<String> lines) {
        String thief = find(data, "strajaThief");
        int errors = 0;
        if (truthy(thief)) {
            try {
                long owed = parseLong(find(data, "strajaOwed"));
                String repRaw = find(data, "strajaRepBackup");
                Integer repBackup = repRaw == null || repRaw.isBlank() ? null : (int) parseLong(repRaw);
                var store = ctx.storage().read();
                if (!store.isThief(uuid.toString())) {
                    store.markThief(uuid.toString(),
                            new com.dwurdy.straja.domain.model.ThiefRecord(owed, repBackup, System.currentTimeMillis()));
                    ctx.storage().write(store);
                    lines.add("player " + uuid + " thief flag imported (owed=" + owed + ")");
                }
            } catch (Exception e) {
                errors++;
                lines.add("player " + uuid + " thief flag FAILED: " + e.getMessage());
            }
        }
        long wantedUntil = parseLong(find(data, "strajaWantedUntil"));
        if (wantedUntil > 0) {
            try {
                var reg = ctx.prisonerRegister().read();
                reg.legacyWantedUntil().put(uuid.toString(), wantedUntil);
                ctx.prisonerRegister().write(reg);
                lines.add("player " + uuid + " wanted-until imported");
            } catch (Exception e) {
                errors++;
                lines.add("player " + uuid + " wanted-until FAILED: " + e.getMessage());
            }
        }
        String jailed = find(data, "cpJailed");
        // cpJailOnRespawn = "hunted death → re-jail on respawn" — same pending custody.
        if (truthy(jailed) || truthy(find(data, "cpJailOnRespawn"))) {
            try {
                var reg = ctx.prisonerRegister().read();
                String key = uuid.toString();
                if (reg.prisoner(key) == null) {
                    var rec = new com.dwurdy.straja.domain.model.PrisonerRegisterRecord(key, key,
                            truthy(find(data, "cpFugitive")) ? "legacy fugitive flag" : "legacy jailed flag");
                    rec.status = truthy(find(data, "cpFugitive"))
                            ? com.dwurdy.straja.domain.model.PrisonerStatus.FUGITIVE
                            : com.dwurdy.straja.domain.model.PrisonerStatus.IN_CELL;
                    reg.put(rec);
                    ctx.prisonerRegister().write(reg);
                    lines.add("player " + uuid + " custody flag imported (" + rec.status + ")");
                }
            } catch (Exception e) {
                errors++;
                lines.add("player " + uuid + " custody flags FAILED: " + e.getMessage());
            }
        }
        return errors;
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return 0L; }
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

    // ------------------------------------------------------------ law enforcement (LAW-001)

    /**
     * Translates the law prototypes' JSON blobs — {@code portCheckpointCfg}
     * (checkpoint sites + global policy), {@code strajaPrisonJail} /
     * {@code portCheckpointJail} (jail register, fines, pendingChest),
     * {@code strajaPrisonCfg} (personal-chest geometry used to resolve locker
     * indices), and {@code strajaStorageCfg}/{@code strajaGoldCfg} (protected
     * storage placement). Idempotent: records merge by id and never duplicate.
     */
    private int migrateLawEnforcement(Map<String, String> data, List<String> lines) {
        int errors = 0;
        errors += migrateCheckpointCfg(data, lines);
        errors += migratePrisonJail(data, lines);
        errors += migrateStorageCfg(data, lines);
        return errors;
    }

    private int migrateCheckpointCfg(Map<String, String> data, List<String> lines) {
        String raw = data.get("portCheckpointCfg");
        if (raw == null || raw.isBlank()) return 0;
        try {
            JsonObject cfg = JsonParser.parseString(raw).getAsJsonObject();
            var store = ctx.lawCheckpoints().read();
            int added = 0;

            JsonObject sites = cfg.has("sites") && cfg.get("sites").isJsonObject()
                    ? cfg.getAsJsonObject("sites") : null;
            if (sites == null && (cfg.has("denyTarget") || cfg.has("doors") || cfg.has("evidence"))) {
                // pre-sites flat layout → single "main" site (prototype parity)
                sites = new JsonObject();
                JsonObject main = new JsonObject();
                for (String k : List.of("denyTarget", "doors", "evidence", "gates", "board", "cb", "exempt", "link")) {
                    if (cfg.has(k)) main.add(k, cfg.get(k));
                }
                sites.add("main", main);
            }
            if (sites != null) {
                for (var entry : sites.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    var record = checkpointFromSite(entry.getKey(), entry.getValue().getAsJsonObject());
                    if (store.checkpoint(record.id) == null) {
                        store.put(record);
                        added++;
                    }
                }
            }
            if (cfg.has("contraband") && cfg.get("contraband").isJsonObject()) {
                cfg.getAsJsonObject("contraband").entrySet().forEach(e ->
                        store.globalIllegalItems().putIfAbsent(e.getKey(), e.getValue().getAsBoolean()));
            }
            if (cfg.has("banned") && cfg.get("banned").isJsonObject()) {
                cfg.getAsJsonObject("banned").keySet().forEach(n -> {
                    if (!store.globalBans().contains(n)) store.globalBans().add(n);
                });
            }
            if (cfg.has("exempt") && cfg.get("exempt").isJsonObject()) {
                cfg.getAsJsonObject("exempt").keySet().forEach(n -> {
                    if (!store.globalExemptions().contains(n)) store.globalExemptions().add(n);
                });
            }
            ctx.lawCheckpoints().write(store);
            lines.add("law checkpoints: " + added + " sites imported (globals: "
                    + store.globalIllegalItems().size() + " items, "
                    + store.globalBans().size() + " bans, "
                    + store.globalExemptions().size() + " exemptions)");
            return 0;
        } catch (Exception e) {
            lines.add("law checkpoints FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static com.dwurdy.straja.domain.model.LawCheckpointRecord checkpointFromSite(
            String name, JsonObject site) {
        var r = new com.dwurdy.straja.domain.model.LawCheckpointRecord();
        r.id = name;
        r.name = name;
        JsonObject deny = site.has("denyTarget") && site.get("denyTarget").isJsonObject()
                ? site.getAsJsonObject("denyTarget") : null;
        if (deny != null) {
            r.pushback = new com.dwurdy.straja.domain.model.PushbackPoint(
                    str(deny, "dim"), num(deny, "x"), num(deny, "y"), num(deny, "z"),
                    (float) num(deny, "yaw"), 0, 0, 0);
            r.dimension = r.pushback.dimension();
        }
        for (var el : arr(site, "doors")) {
            if (el.isJsonObject()) r.doors.add(pointFrom(el.getAsJsonObject()));
        }
        for (var el : arr(site, "evidence")) {
            if (el.isJsonObject()) r.evidenceChests.add(pointFrom(el.getAsJsonObject()));
        }
        JsonObject evidenceSingle = obj(site, "evidence"); // pre-normalized saves may hold a lone object
        if (evidenceSingle != null && r.evidenceChests.isEmpty()) {
            r.evidenceChests.add(pointFrom(evidenceSingle));
        }
        for (var el : arr(site, "gates")) {
            if (!el.isJsonObject()) continue;
            JsonObject g = el.getAsJsonObject();
            JsonObject a = obj(g, "a"), b = obj(g, "b"), from = obj(g, "from");
            if (a == null || b == null || from == null) continue; // no 'from' = undirected, prototype skips
            r.gates.add(new com.dwurdy.straja.domain.model.GateLane(
                    str(g, "dim"), num(a, "x"), num(a, "z"), num(b, "x"), num(b, "z"),
                    num(from, "x"), num(from, "z")));
        }
        JsonObject board = obj(site, "board");
        if (board != null) {
            r.boardZone = new com.dwurdy.straja.domain.model.BoardingZone(
                    str(board, "dim"), num(board, "x1"), num(board, "z1"),
                    num(board, "x2"), num(board, "z2"), num(board, "y"));
        }
        JsonObject cb = obj(site, "cb");
        if (cb != null) {
            for (var e : cb.entrySet()) {
                if (e.getValue().getAsBoolean()) r.localIllegalItems.add(e.getKey());
                else r.localAllowedItems.add(e.getKey());
            }
        }
        JsonObject exempt = obj(site, "exempt");
        if (exempt != null) r.exemptions.addAll(exempt.keySet());
        String link = str(site, "link");
        if (!link.isBlank()) r.linkedCheckpointId = link;
        if (r.dimension.isBlank() || "minecraft:overworld".equals(r.dimension)) {
            // pick a declared dimension off any configured point
            for (var p : r.doors) { r.dimension = p.dimension(); break; }
            if (r.dimension.isBlank() || "minecraft:overworld".equals(r.dimension)) {
                for (var g : r.gates) { r.dimension = g.dimension(); break; }
            }
            if (r.dimension.isBlank() || "minecraft:overworld".equals(r.dimension)) {
                if (r.boardZone != null) r.dimension = r.boardZone.dimension();
            }
        }
        return r;
    }

    private int migratePrisonJail(Map<String, String> data, List<String> lines) {
        String raw = data.get("strajaPrisonJail");
        if (raw == null || raw.isBlank()) raw = data.get("portCheckpointJail"); // legacy alias
        if (raw == null || raw.isBlank()) return 0;
        try {
            JsonObject jail = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject pcfg = parseObj(data.get("strajaPrisonCfg"));
            com.google.gson.JsonArray pcells = pcfg != null && pcfg.has("pcells")
                    && pcfg.get("pcells").isJsonArray() ? pcfg.getAsJsonArray("pcells") : null;
            if (pcells == null) {
                // Pre-split saves keep cell geometry on portCheckpointCfg.
                JsonObject pccfg = parseObj(data.get("portCheckpointCfg"));
                if (pccfg != null && pccfg.has("pcells") && pccfg.get("pcells").isJsonArray()) {
                    pcells = pccfg.getAsJsonArray("pcells");
                }
            }

            var reg = ctx.prisonerRegister().read();
            int added = 0;
            JsonObject jailed = obj(jail, "jailed");
            JsonObject fines = obj(jail, "fines");
            if (jailed != null) {
                for (var e : jailed.entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    String name = e.getKey();
                    JsonObject entry = e.getValue().getAsJsonObject();
                    String uuid = com.dwurdy.straja.domain.model.PrisonerRegisterStore.legacyUuid(name);
                    if (reg.prisoner(uuid) != null) continue;
                    if (reg.prisonerByName(name) != null) continue;
                    var rec = new com.dwurdy.straja.domain.model.PrisonerRegisterRecord(uuid, name, str(entry, "reason"));
                    rec.status = "fugitive".equals(str(entry, "status"))
                            ? com.dwurdy.straja.domain.model.PrisonerStatus.FUGITIVE
                            : com.dwurdy.straja.domain.model.PrisonerStatus.IN_CELL;
                    rec.bookedAt = (long) num(entry, "t");
                    rec.arrestCount = (int) num(entry, "arrests");
                    rec.confiscatedFully = entry.has("confiscated") && entry.get("confiscated").getAsBoolean();
                    rec.arrestSite = str(entry, "site");
                    if (fines != null && fines.has(name)) rec.outstandingFines = (int) num(fines, name);
                    int jcell = entry.has("jcell") ? (int) num(entry, "jcell") : -1;
                    if (jcell >= 0) rec.assignedCellId = "jcell:" + jcell;
                    int pc = entry.has("pchest") ? (int) num(entry, "pchest") : -1;
                    if (pc >= 0 && pcells != null && pc < pcells.size() && pcells.get(pc).isJsonObject()) {
                        JsonObject pair = pcells.get(pc).getAsJsonObject();
                        JsonObject a = obj(pair, "a"), b = obj(pair, "b");
                        if (a != null) rec.personalLocker.add(pointFrom(a));
                        if (b != null) rec.personalLocker.add(pointFrom(b));
                    }
                    JsonObject items = obj(entry, "items");
                    if (items != null) {
                        for (var it : items.entrySet()) {
                            if (it.getValue().isJsonObject()) {
                                JsonObject io = it.getValue().getAsJsonObject();
                                int count = (int) num(io, "count");
                                rec.confiscatedSummary.add(count + " x " + it.getKey());
                                rec.arrestSnapshot.add(new com.dwurdy.straja.domain.model.SnapshotItem(
                                        "legacy", it.getKey(), count, null, str(io, "name")));
                            }
                        }
                    }
                    reg.put(rec);
                    added++;
                }
            }
            JsonObject pending = obj(jail, "pendingChest");
            if (pending != null) {
                // Prototype shape: pendingChest[realName] = pcIdx (scalar), resolved
                // through pcells[pcIdx] = {a,b} into coordinate locker keys.
                for (var e : pending.entrySet()) {
                    String name = e.getKey();
                    String uuid = com.dwurdy.straja.domain.model.PrisonerRegisterStore.legacyUuid(name);
                    List<String> keys = new ArrayList<>();
                    if (e.getValue().isJsonPrimitive()) {
                        int pc = e.getValue().getAsInt();
                        if (pc >= 0 && pcells != null && pc < pcells.size() && pcells.get(pc).isJsonObject()) {
                            JsonObject pair = pcells.get(pc).getAsJsonObject();
                            JsonObject a = obj(pair, "a"), b = obj(pair, "b");
                            if (a != null) keys.add(pointFrom(a).key());
                            if (b != null) keys.add(pointFrom(b).key());
                        }
                    } else if (e.getValue().isJsonArray()) {
                        // Defensive: accept a list of indices too.
                        for (var el : e.getValue().getAsJsonArray()) {
                            int pc = el.getAsInt();
                            if (pc >= 0 && pcells != null && pc < pcells.size() && pcells.get(pc).isJsonObject()) {
                                JsonObject pair = pcells.get(pc).getAsJsonObject();
                                JsonObject a = obj(pair, "a"), b = obj(pair, "b");
                                if (a != null) keys.add(pointFrom(a).key());
                                if (b != null) keys.add(pointFrom(b).key());
                            }
                        }
                    }
                    if (!keys.isEmpty()) reg.reserveLockers(uuid, keys);
                }
            }
            if (fines != null) {
                // Fines accrue on release too — keep them for names never booked.
                for (var e : fines.entrySet()) {
                    if (jailed == null || !jailed.has(e.getKey())) {
                        reg.legacyFines().put(e.getKey(), e.getValue().getAsInt());
                    }
                }
            }
            ctx.prisonerRegister().write(reg);
            lines.add("prisoner register: " + added + " imported, " + reg.pendingLockers().size() + " pending lockers");
            return 0;
        } catch (Exception e) {
            lines.add("prisoner register FAILED: " + e.getMessage());
            return 1;
        }
    }

    private int migrateStorageCfg(Map<String, String> data, List<String> lines) {
        String raw = data.get("strajaStorageCfg");
        if (raw == null || raw.isBlank()) raw = data.get("strajaGoldCfg"); // gold-era key
        if (raw == null || raw.isBlank()) return 0;
        try {
            JsonObject cfg = JsonParser.parseString(raw).getAsJsonObject();
            var store = ctx.storage().read();
            var current = store.setup();
            if (current.dest() != null || current.zone() != null || !current.chests().isEmpty()) {
                lines.add("storage setup: already configured — legacy import skipped");
                return 0;
            }
            var destObj = obj(cfg, "dest");
            var dest = destObj == null ? null : pointFrom(destObj);
            var zoneObj = obj(cfg, "zone");
            if (zoneObj == null) zoneObj = obj(cfg, "vault"); // pre-rename field
            com.dwurdy.straja.domain.model.StorageZone zone = null;
            if (zoneObj != null) {
                var min = zoneObj.has("min") && zoneObj.get("min").isJsonArray() ? zoneObj.getAsJsonArray("min") : null;
                var max = zoneObj.has("max") && zoneObj.get("max").isJsonArray() ? zoneObj.getAsJsonArray("max") : null;
                if (min != null && max != null && min.size() == 3 && max.size() == 3) {
                    zone = new com.dwurdy.straja.domain.model.StorageZone(str(zoneObj, "dim"),
                            min.get(0).getAsInt(), min.get(1).getAsInt(), min.get(2).getAsInt(),
                            max.get(0).getAsInt(), max.get(1).getAsInt(), max.get(2).getAsInt());
                }
            }
            var chests = new ArrayList<com.dwurdy.straja.domain.model.StoragePoint>();
            for (var el : arr(cfg, "chests")) {
                if (el.isJsonObject()) chests.add(pointFrom(el.getAsJsonObject()));
            }
            store.setup(new com.dwurdy.straja.domain.model.StorageSetup(dest, zone, chests));
            ctx.storage().write(store);
            lines.add("storage setup: imported (zone=" + (zone != null) + ", chests=" + chests.size()
                    + ", dest=" + (dest != null) + ")");
            return 0;
        } catch (Exception e) {
            lines.add("storage setup FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static JsonObject parseObj(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            var el = JsonParser.parseString(raw);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static com.google.gson.JsonArray arr(JsonObject o, String key) {
        if (o != null && o.has(key) && o.get(key).isJsonArray()) return o.getAsJsonArray(key);
        return new com.google.gson.JsonArray();
    }

    private static double num(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0.0;
    }

    private static com.dwurdy.straja.domain.model.StoragePoint pointFrom(JsonObject o) {
        String dim = str(o, "dim");
        if (dim.isBlank()) dim = "minecraft:overworld";
        return new com.dwurdy.straja.domain.model.StoragePoint(dim,
                (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"));
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
