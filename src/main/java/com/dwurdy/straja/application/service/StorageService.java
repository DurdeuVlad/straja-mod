package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.domain.model.StorageSetup;
import com.dwurdy.straja.domain.model.StorageWatchStore;
import com.dwurdy.straja.domain.model.StorageZone;
import com.dwurdy.straja.domain.model.ThiefRecord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Straja Storage: protected-zone theft watch, deposit routing, and guard aggro.
 *
 * <p>Native port of the KubeJS {@code straja_gold} prototype, generalized from
 * gold to any configured set of watched items (valued in abstract units). All
 * economics stay server-authoritative: container reads, suspect attribution,
 * faction pinning and NPC targeting go through ports, never client state.
 */
public final class StorageService {
    public static final String PICK_DEST = "dest";
    public static final String PICK_ZONE = "zone";
    public static final String PICK_CHEST = "chest";
    private static final Set<String> PICK_MODES = Set.of(PICK_DEST, PICK_ZONE, PICK_CHEST);
    private static final int DEFAULT_REP = 1000;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final BoloService bolos;
    private final PrisonService prison;
    /** M4: cuffed suspects under escort are already caught — guards hold fire. */
    private CustodyService custody;
    /** LAW-007: the authoritative wanted surface once wired; BOLO fallback otherwise. */
    private WantedService wantedService;

    // Transient runtime state — pick sessions, container snapshots and the
    // aggro bookkeeping are inherently per-boot, matching the prototype.
    private final Map<UUID, String> pickModes = new HashMap<>();
    private final Map<UUID, StoragePoint> zoneCorners = new HashMap<>();
    private final Map<String, Integer> chestCache = new HashMap<>();
    private final Map<String, Integer> pendingDeposits = new HashMap<>();
    private final Set<UUID> aggroMarked = new HashSet<>();
    private int tickCounter;

    public StorageService(StrajaContext ctx, PlayerService players, AuditService audit,
            BoloService bolos, PrisonService prison) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.bolos = bolos;
        this.prison = prison;
    }

    /** Late-bound: custody is constructed after storage in the runtime graph. */
    public void useCustody(CustodyService service) {
        this.custody = service;
    }

    /** Late-bound: the wanted service is constructed after storage's deps. */
    public void useWanted(WantedService service) {
        this.wantedService = service;
    }

    private boolean isWantedPlayer(UUID uuid) {
        return wantedService != null ? wantedService.isWanted(uuid)
                : wantedUuids().contains(uuid.toString());
    }

    /** Wanted uuid set for a scan pass — parsed once, not per player. */
    private Set<String> wantedSnapshot() {
        return wantedService != null ? wantedService.wantedUuids() : wantedUuids();
    }

    private boolean underEscort(PlayerGateway p) {
        if (wantedService != null) return wantedService.isUnderEscort(p);
        return custody != null && custody.escortOfficerWithin(
                p, ctx.policies().escortTetherRadius) != null;
    }

    private StorageWatchStore store() {
        return ctx.storage().read();
    }

    private void save(StorageWatchStore store) {
        ctx.storage().write(store);
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    // ---------------------------------------------------------------- queries

    public boolean isThief(UUID uuid) {
        return store().isThief(uuid.toString());
    }

    /** LAW-007: the wanted service is authoritative; BOLO is the fallback. */
    public boolean isWanted(UUID uuid) {
        return isWantedPlayer(uuid);
    }

    private Set<String> wantedUuids() {
        var out = new HashSet<String>();
        for (var record : bolos.active()) {
            if (record != null && record.subjectUuid != null) out.add(record.subjectUuid);
        }
        return out;
    }

    /**
     * An arrested thief is in custody — the hunt ends and their faction rep is
     * restored (booking already consumed the punishment path).
     */
    public void onArrested(UUID uuid) {
        var store = store();
        String key = uuid.toString();
        if (store.thief(key) == null) return;
        var player = ctx.server().findPlayer(uuid);
        if (player != null) {
            clearThief(store, player, Release.ARRESTED);
        } else {
            // Offline target: drop the flag and finish the team quest — the
            // quest command is team-scoped and works without the player online.
            store.clearThief(key);
            ctx.npcGuards().finishQuestForTeam(
                    ctx.policies().storageHuntTeam, ctx.policies().storageHuntQuestId);
            audit.record("storage_thief_cleared", "storage", "", key, key, "ARRESTED", "offline");
        }
        save(store);
    }

    /** A flagged thief dying (the hunt's resolution) clears the debt flag. */
    public void onPlayerDeath(PlayerGateway player) {
        var store = store();
        if (store.thief(player.uuid().toString()) == null) return;
        clearThief(store, player, Release.DIED);
        save(store);
    }

    public long owedUnits(UUID uuid) {
        ThiefRecord rec = store().thief(uuid.toString());
        return rec == null ? 0 : rec.owed();
    }

    private int unitValue(String itemId) {
        return ctx.policies().storageWatchedItemUnits.getOrDefault(itemId, 0);
    }

    private boolean inZone(StorageWatchStore store, String dimension, int x, int y, int z) {
        StorageZone zone = store.setup().zone();
        return zone != null && zone.contains(dimension, x, y, z);
    }

    private boolean isExempt(PlayerGateway player) {
        var exempt = ctx.policies().storageExemptPlayers;
        return exempt.contains(player.name()) || exempt.contains(player.uuid().toString());
    }

    private boolean isAllied(PlayerGateway player) {
        String team = ctx.factions().teamOf(player);
        return team != null && ctx.policies().storageAlliedTeams.contains(team);
    }

    private boolean isSurvivalOrAdventure(PlayerGateway player) {
        String mode = player.gameModeName();
        return "survival".equals(mode) || "adventure".equals(mode);
    }

    private boolean isStrajaMember(PlayerGateway player) {
        String team = ctx.factions().teamOf(player);
        if (ctx.policies().storageHuntTeam.equals(team)) return true;
        var state = players.state(player);
        return state != null && Rank.of(state.rank).atLeast(Rank.STAGIAR);
    }

    private boolean jailed(PlayerGateway player) {
        return prison.activeSentence(player) != null;
    }

    // ------------------------------------------------------- theft lifecycle

    private void markThief(StorageWatchStore store, PlayerGateway player, long units) {
        String key = player.uuid().toString();
        ThiefRecord rec = store.thief(key);
        if (rec == null) {
            Integer rep = ctx.npcGuards().factionPoints(player.uuid(), ctx.policies().storageFactionId);
            rec = new ThiefRecord(0, rep, now());
            ctx.npcGuards().setFactionPoints(player.uuid(), ctx.policies().storageFactionId, 0);
            announceTheft(player.name());
            ctx.npcGuards().startQuestForTeam(ctx.policies().storageHuntTeam, ctx.policies().storageHuntQuestId);
            audit.record("storage_thief_marked", "storage", "", player.name(), key,
                    "FLAGGED", "units=" + units);
        }
        store.markThief(key, rec.addOwed(units));
    }

    private enum Release { REPAID, DIED, ARRESTED }

    private void creditThief(StorageWatchStore store, PlayerGateway player, long units) {
        ThiefRecord rec = store.thief(player.uuid().toString());
        if (rec == null) return;
        rec = rec.reducedBy(units);
        if (rec.owed() <= 0) {
            clearThief(store, player, Release.REPAID);
        } else {
            store.markThief(player.uuid().toString(), rec);
        }
    }

    private void clearThief(StorageWatchStore store, PlayerGateway player, Release release) {
        String key = player.uuid().toString();
        ThiefRecord rec = store.thief(key);
        if (rec == null) return;
        store.clearThief(key);
        int rep = rec.repBackup() != null ? rec.repBackup() : DEFAULT_REP;
        ctx.npcGuards().setFactionPoints(player.uuid(), ctx.policies().storageFactionId, rep);
        ctx.npcGuards().finishQuestForTeam(ctx.policies().storageHuntTeam, ctx.policies().storageHuntQuestId);
        if (!store.thieves().isEmpty()) {
            ctx.npcGuards().startQuestForTeam(ctx.policies().storageHuntTeam, ctx.policies().storageHuntQuestId);
        }
        String msgKey = switch (release) {
            case DIED -> "straja.storage.thief_died";
            case ARRESTED -> "straja.storage.thief_arrested";
            default -> "straja.storage.thief_cleared";
        };
        for (var p : ctx.server().onlinePlayers()) p.tellKey(msgKey, player.name());
        audit.record("storage_thief_cleared", "storage", "", player.name(), key,
                release.name(), "rep=" + rep);
    }

    private void announceTheft(String thiefName) {
        for (var p : ctx.server().onlinePlayers()) {
            p.title("straja.storage.theft_title", "straja.storage.theft_subtitle", thiefName);
        }
    }

    private boolean eligibleActor(PlayerGateway player) {
        if (player == null || !player.isOnline()) return false;
        if (!isSurvivalOrAdventure(player) || isExempt(player)) return false;
        return !(ctx.policies().storageAlliesHandleGoods && isAllied(player));
    }

    // ------------------------------------------------------------- triggers

    /** Watched block broken inside the zone. */
    public void onBlockBroken(PlayerGateway player, String dimension, int x, int y, int z, String blockId) {
        var store = store();
        int value = unitValue(blockId);
        if (value <= 0 || !inZone(store, dimension, x, y, z)) return;
        if (!eligibleActor(player)) return;
        markThief(store, player, value);
        save(store);
    }

    /** Watched item entity picked up inside the zone. */
    public void onItemPickedUp(PlayerGateway player, String dimension, int x, int y, int z,
            String itemId, int count) {
        var store = store();
        int value = unitValue(itemId);
        if (value <= 0 || count <= 0 || !inZone(store, dimension, x, y, z)) return;
        if (!eligibleActor(player)) return;
        markThief(store, player, (long) value * count);
        save(store);
    }

    /** Watched block placed inside the zone by a flagged thief — repayment. */
    public void onBlockPlaced(PlayerGateway player, String dimension, int x, int y, int z, String blockId) {
        var store = store();
        int value = unitValue(blockId);
        if (value <= 0 || !inZone(store, dimension, x, y, z)) return;
        if (player == null || !store.isThief(player.uuid().toString())) return;
        creditThief(store, player, value);
        save(store);
    }

    /** A Straja member logging in mid-incident joins the hunt. */
    public void onLogin(PlayerGateway player) {
        var store = store();
        if (store.thieves().isEmpty() || !isStrajaMember(player)) return;
        ctx.npcGuards().startQuestForPlayer(player.uuid(), ctx.policies().storageHuntQuestId);
    }

    // ------------------------------------------------------------- periodic

    /** Called once per server tick; internally throttled by policy periods. */
    public void tick() {
        tickCounter++;
        var p = ctx.policies();
        if (tickCounter % Math.max(1, p.storageChestPollTicks) == 0) pollWatchedChests();
        if (tickCounter % Math.max(1, p.storageAggroPeriodTicks) == 0) aggroScan();
        if (tickCounter % Math.max(1, p.storageEnforcePeriodTicks) == 0) enforceRep();
    }

    /**
     * Negative delta → flag the nearest eligible suspect (allied hands closer
     * veto attribution); positive delta → credit the nearest flagged payer.
     */
    private void pollWatchedChests() {
        var store = store();
        var chests = store.setup().chests();
        if (chests.isEmpty()) return;
        boolean changed = false;
        for (StoragePoint chest : chests) {
            int count = ctx.containers().countUnits(chest.dimension(), chest.x(), chest.y(), chest.z(),
                    ctx.policies().storageWatchedItemUnits);
            if (count < 0) continue;
            // Canonical key: both halves of a joined chest share one cache
            // entry, so registering both halves can never double-attribute.
            String key = ctx.containers().canonicalKey(
                    chest.dimension(), chest.x(), chest.y(), chest.z());
            // Consume our own deposits first: when the chest was never polled
            // (fresh boot/reset), the deposit is folded into the baseline so
            // the next delta cannot attribute our deposit to a bystander.
            int pending = pendingDeposits.containsKey(key) ? pendingDeposits.remove(key) : 0;
            Integer prev = chestCache.get(key);
            chestCache.put(key, count);
            if (prev == null) continue;
            int delta = count - prev - pending;
            if (delta == 0) continue;
            if (delta < 0) {
                var suspect = nearestPlayer(store, chest, ctx.policies().storageSuspectRange, false);
                var ally = nearestAlly(chest, ctx.policies().storageSuspectRange);
                if (suspect.player != null && (ally.player == null || suspect.dist <= ally.dist)) {
                    markThief(store, suspect.player, -delta);
                    changed = true;
                }
            } else {
                var payer = nearestPlayer(store, chest, ctx.policies().storageSuspectRange, true);
                if (payer.player != null) {
                    creditThief(store, payer.player, delta);
                    changed = true;
                }
            }
        }
        if (changed) save(store);
    }

    private record Nearest(PlayerGateway player, double dist) {}

    /** Reads the in-flight store so same-tick marks/clears from other chests are visible. */
    private Nearest nearestPlayer(StorageWatchStore store, StoragePoint at, double range, boolean flaggedOnly) {
        PlayerGateway best = null;
        double bestDist = Double.MAX_VALUE;
        for (var p : ctx.server().onlinePlayers()) {
            if (!at.dimension().equals(p.dimension())) continue;
            if (!isSurvivalOrAdventure(p) || isExempt(p)) continue;
            if (flaggedOnly) {
                if (!store.isThief(p.uuid().toString())) continue;
            } else if (ctx.policies().storageAlliesHandleGoods && isAllied(p)) {
                continue;
            }
            double d = distSq(p, at);
            if (d <= range * range && d < bestDist) {
                best = p;
                bestDist = d;
            }
        }
        return new Nearest(best, bestDist);
    }

    private Nearest nearestAlly(StoragePoint at, double range) {
        PlayerGateway best = null;
        double bestDist = Double.MAX_VALUE;
        for (var p : ctx.server().onlinePlayers()) {
            if (!at.dimension().equals(p.dimension()) || !isAllied(p)) continue;
            double d = distSq(p, at);
            if (d <= range * range && d < bestDist) {
                best = p;
                bestDist = d;
            }
        }
        return new Nearest(best, bestDist);
    }

    private static double distSq(PlayerGateway p, StoragePoint at) {
        double dx = p.x() - at.x(), dy = p.y() - at.y(), dz = p.z() - at.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Guards of the configured faction target flagged thieves/wanted on sight. */
    private void aggroScan() {
        var guards = ctx.npcGuards();
        if (!guards.available()) return;
        var wanted = wantedSnapshot();
        var watchStore = store();
        for (var p : ctx.server().onlinePlayers()) {
            boolean hostile = (watchStore.isThief(p.uuid().toString())
                    || wanted.contains(p.uuid().toString()))
                    && !underEscort(p) && !jailed(p) && isSurvivalOrAdventure(p) && !isExempt(p);
            if (!hostile && !aggroMarked.contains(p.uuid())) continue;
            var near = guards.guardsNear(p.dimension(), p.x(), p.y(), p.z(),
                    ctx.policies().storageAggroRange, ctx.policies().storageFactionId);
            if (near.isEmpty()) {
                if (!hostile) aggroMarked.remove(p.uuid());
                continue;
            }
            for (var npc : near) {
                if (!hostile) {
                    guards.clearTargetIfTargeting(npc.id(), p.uuid());
                    continue;
                }
                if (!guards.hasLineOfSight(npc.id(), p.uuid())) continue;
                int range = guards.aggroRange(npc.id(), ctx.policies().storageAggroRange);
                double dx = npc.x() - p.x(), dy = npc.y() - p.y(), dz = npc.z() - p.z();
                if (dx * dx + dy * dy + dz * dz > range * range) continue;
                guards.setTarget(npc.id(), p.uuid());
                aggroMarked.add(p.uuid());
            }
            if (!hostile) aggroMarked.remove(p.uuid());
        }
    }

    /** Guards keep the flagged player's faction pinned at 0 unless jailed. */
    private void enforceRep() {
        var watchStore = store();
        for (var p : ctx.server().onlinePlayers()) {
            if (jailed(p)) continue;
            if (watchStore.isThief(p.uuid().toString())) {
                ctx.npcGuards().setFactionPoints(p.uuid(), ctx.policies().storageFactionId, 0);
            }
        }
    }

    // -------------------------------------------------------------- deposit

    public record DepositResult(boolean success, int inserted, int leftover) {}

    /**
     * The merchant-desk deposit: goods arrive physically at the dest chest.
     * The attributed player's name is audit-only — the prototype consumed no
     * inventory because the NPC had already taken the items.
     */
    public DepositResult deposit(String playerName, String itemId, int count) {
        int value = unitValue(itemId);
        if (value <= 0 || count <= 0) return new DepositResult(false, 0, count);
        var store = store();
        StoragePoint dest = store.setup().dest();
        if (dest == null || !ctx.containers().isContainer(dest.dimension(), dest.x(), dest.y(), dest.z())) {
            return new DepositResult(false, 0, count);
        }
        int leftover = ctx.containers().insert(dest.dimension(), dest.x(), dest.y(), dest.z(), itemId, count);
        if (leftover < 0) return new DepositResult(false, 0, count); // item id does not resolve
        int inserted = count - leftover;
        String destKey = ctx.containers().canonicalKey(dest.dimension(), dest.x(), dest.y(), dest.z());
        if (inserted > 0 && isWatched(store, destKey)) {
            // Only a watched chest needs the netting entry — unwatched dests
            // would accumulate the map forever for no benefit.
            pendingDeposits.merge(destKey, inserted * value, Integer::sum);
        }
        if (leftover > 0) {
            ctx.containers().dropItem(dest.dimension(), dest.x(), dest.y(), dest.z(), itemId, leftover);
        }
        audit.record("storage_deposit", "storage", "", playerName, "", "OK",
                count + "x " + itemId + " (leftover " + leftover + ")");
        return new DepositResult(true, inserted, leftover);
    }

    private boolean isWatched(StorageWatchStore store, String canonicalKey) {
        return store.setup().chests().stream().anyMatch(c ->
                ctx.containers().canonicalKey(c.dimension(), c.x(), c.y(), c.z()).equals(canonicalKey));
    }

    // --------------------------------------------------------------- picking

    /** Arms a pick mode; {@code off} disarms. Returns false for unknown modes. */
    public boolean setPickMode(PlayerGateway admin, String mode) {
        if (mode == null) return false;
        if ("off".equals(mode)) {
            pickModes.remove(admin.uuid());
            zoneCorners.remove(admin.uuid());
            return true;
        }
        if (!PICK_MODES.contains(mode)) return false;
        pickModes.put(admin.uuid(), mode);
        if (!PICK_ZONE.equals(mode)) zoneCorners.remove(admin.uuid());
        return true;
    }

    public String pickMode(UUID uuid) {
        return pickModes.get(uuid);
    }

    /**
     * Right-click consumed by an armed pick mode. Returns true when the click
     * was consumed (caller should cancel the interaction).
     */
    public boolean onPickClick(PlayerGateway admin, String dimension, int x, int y, int z) {
        String mode = pickModes.get(admin.uuid());
        if (mode == null) return false;
        var store = store();
        StoragePoint point = new StoragePoint(dimension, x, y, z);
        switch (mode) {
            case PICK_DEST -> {
                if (!ctx.containers().isContainer(dimension, x, y, z)) {
                    admin.refuse("straja.storage.pick_not_container", "straja.remedy.fix_retry");
                    return true;
                }
                store.setup(store.setup().withDest(point));
                pickModes.remove(admin.uuid());
                admin.tellKey("straja.storage.pick_dest", point.key());
            }
            case PICK_ZONE -> {
                var corner = zoneCorners.get(admin.uuid());
                if (corner == null) {
                    zoneCorners.put(admin.uuid(), point);
                    admin.tellKey("straja.storage.pick_zone_1", point.key());
                    return true;
                }
                if (!corner.dimension().equals(dimension)) {
                    zoneCorners.remove(admin.uuid());
                    admin.refuse("straja.storage.zone_dim_mismatch", "straja.remedy.retry");
                    return true;
                }
                zoneCorners.remove(admin.uuid());
                pickModes.remove(admin.uuid());
                store.setup(store.setup().withZone(new StorageZone(
                        dimension, corner.x(), corner.y(), corner.z(), x, y, z)));
                admin.tellKey("straja.storage.pick_zone_2", corner.key(), point.key());
            }
            case PICK_CHEST -> {
                if (!ctx.containers().isContainer(dimension, x, y, z)) {
                    admin.refuse("straja.storage.pick_not_container", "straja.remedy.fix_retry");
                    return true;
                }
                String canon = ctx.containers().canonicalKey(dimension, x, y, z);
                boolean dup = store.setup().chests().stream().anyMatch(c ->
                        ctx.containers().canonicalKey(c.dimension(), c.x(), c.y(), c.z()).equals(canon));
                if (dup) {
                    admin.tellKey("straja.storage.pick_chest_dup", point.key());
                } else {
                    store.setup(store.setup().addChest(point));
                    admin.tellKey("straja.storage.pick_chest_added", point.key());
                }
            }
            default -> { return false; }
        }
        save(store);
        return true;
    }

    public void resetConfig(PlayerGateway admin) {
        var store = store();
        store.setup(new StorageSetup(null, null, List.of()));
        chestCache.clear();
        pendingDeposits.clear();
        pickModes.clear();
        zoneCorners.clear();
        save(store);
        admin.tellKey("straja.storage.cfg_reset");
        audit.record("storage_cfg_reset", admin.name(), admin.uuid().toString(), "", "", "RESET", "");
    }

    // ------------------------------------------------------------ reporting

    public void status(PlayerGateway admin) {
        var s = store();
        var setup = s.setup();
        admin.tellKey("straja.storage.status_dest",
                setup.dest() == null ? "-" : setup.dest().key());
        admin.tellKey("straja.storage.status_zone",
                setup.zone() == null ? "-" : zoneKey(setup.zone()));
        admin.tellKey("straja.storage.status_chests", setup.chests().size());
        admin.tellKey("straja.storage.status_thieves", s.thieves().size());
    }

    public void hunted(PlayerGateway admin) {
        var store = store();
        if (store.thieves().isEmpty()) {
            admin.tellKey("straja.storage.hunted_none");
            return;
        }
        admin.tellKey("straja.storage.hunted_header", store.thieves().size());
        for (var e : store.thieves().entrySet()) {
            if (e.getValue() == null) continue; // partial JSON can leave null records
            var target = ctx.server().findPlayer(e.getKey());
            String name = target != null ? target.name() : e.getKey();
            String where = target != null && target.isOnline()
                    ? target.dimension() + " " + (int) target.x() + "," + (int) target.y() + "," + (int) target.z()
                    : "offline";
            admin.tellKey("straja.storage.hunted_entry", name, e.getValue().owed(), where);
        }
    }

    private static String zoneKey(StorageZone z) {
        return z.dimension() + " " + z.ax() + "," + z.ay() + "," + z.az()
                + " -> " + z.bx() + "," + z.by() + "," + z.bz();
    }
}
