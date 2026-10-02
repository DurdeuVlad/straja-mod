package com.dwurdy.straja.support;

import com.dwurdy.straja.adapter.out.persistence.SavedPlayerStateRepository;
import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.*;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic fakes for pure unit tests — no Minecraft runtime needed. */
public final class Fakes {
    private Fakes() {}

    // ---------------------------------------------------------------- clock/ids

    public static final class FixedClock implements Clock {
        public long now;
        public FixedClock(long now) { this.now = now; }
        @Override public long nowMillis() { return now; }
        public void advance(long ms) { now += ms; }
    }

    public static final class SeqIds implements IdGenerator {
        private long next;
        @Override public String newId(String prefix) { return prefix + ++next; }
        @Override public String token() { return "tok" + (++next); }
    }

    // ---------------------------------------------------------------- inventory

    public static final class TestInventory implements InventoryView {
        public final List<ItemView> slots;

        public TestInventory(int slotCount) {
            slots = new ArrayList<>();
            for (int i = 0; i < slotCount; i++) slots.add(ItemView.EMPTY);
        }

        @Override public int slots() { return slots.size(); }
        @Override public ItemView stackAt(int slot) { return slots.get(slot); }

        @Override public ItemView extract(int slot, int amount) {
            ItemView stack = slots.get(slot);
            if (stack.isEmpty() || amount <= 0) return ItemView.EMPTY;
            int taken = Math.min(amount, stack.count());
            slots.set(slot, taken == stack.count() ? ItemView.EMPTY : stack.withCount(stack.count() - taken));
            return stack.withCount(taken);
        }

        public boolean insert(ItemView stack) {
            for (int i = 0; i < slots.size(); i++) {
                ItemView existing = slots.get(i);
                if (existing.id().equals(stack.id()) && existing.customData().equals(stack.customData())
                        && existing.count() + stack.count() <= existing.maxStackSize()) {
                    slots.set(i, existing.withCount(existing.count() + stack.count()));
                    return true;
                }
            }
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).isEmpty()) {
                    slots.set(i, stack);
                    return true;
                }
            }
            return false;
        }

        @Override public boolean canReceive(List<ItemSpec> items) {
            // strict: one spec per free slot unless it merges into an existing stack
            TestInventory copy = new TestInventory(slots.size());
            for (int i = 0; i < slots.size(); i++) copy.slots.set(i, slots.get(i));
            for (ItemSpec spec : items) {
                ItemView probe = new ItemView(spec.id(), spec.count(), 64, spec.customData());
                if (!copy.insert(probe)) return false;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- player

    public static final class TestPlayer implements PlayerGateway {
        public TestServer server;
        public final UUID uuid;
        public String name;
        public boolean online = true;
        public boolean op;
        public String dimension = "minecraft:overworld";
        public double x, y, z;
        public double health = 20, maxHealth = 20, absorption;
        public final TestInventory inventory;
        public final List<String> messages = new ArrayList<>();
        public final List<String> actionbarMessages = new ArrayList<>();
        public int selectedSlot = -1;
        public final List<String> effects = new ArrayList<>();
        public String gameMode = "survival";
        public UUID vehicleUuid;
        public UUID passengerUuid;
        /** When > 0, the next N giveVerified calls fail as if delivery broke. */
        public int failVerifiedCalls;
        /** Simulates riding a boat/raft for checkpoint boarding checks. */
        public boolean ridingBoat;
        /** Last velocity applied via setVelocity. */
        public double vx, vy, vz;
        /** Last yaw applied via the oriented teleport. */
        public float lastTeleportYaw;
        /** Sprint state — setSprinting writes here (escort suppresses it). */
        public boolean sprinting;
        /** Written books handed over via giveWrittenBook: "title|page1//page2". */
        public final List<String> books = new ArrayList<>();
        /** Items that did not fit the inventory on giveStack — spilled drops. */
        public final List<String> spilled = new ArrayList<>();

        public TestPlayer(String name, int slots) {
            this.uuid = UUID.nameUUIDFromBytes(name.getBytes());
            this.name = name;
            this.inventory = new TestInventory(slots);
        }

        @Override public UUID uuid() { return uuid; }
        @Override public String name() { return name; }
        @Override public boolean isOnline() { return online; }
        @Override public boolean isOp() { return op; }
        @Override public String dimension() { return dimension; }
        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double health() { return health; }
        @Override public double maxHealth() { return maxHealth; }
        @Override public double absorption() { return absorption; }
        @Override public void setHealth(double value) { health = value; }
        @Override public void tell(String text) { messages.add(text); }
        @Override public void refuse(String reasonKey, String remedyKey, Object... reasonArgs) {
            messages.add(com.dwurdy.straja.adapter.in.test.VirtualLang.refusal(
                    reasonKey, remedyKey, reasonArgs));
        }
        @Override public void actionbar(String text) { actionbarMessages.add(text); }
        @Override public String gameModeName() { return gameMode; }
        @Override public void setGameMode(String mode) { if (mode != null && !mode.isBlank()) gameMode = mode; }
        @Override public void setSprinting(boolean sprint) { sprinting = sprint; }
        @Override public void giveWrittenBook(String title, String author, List<String> pages) {
            books.add(title + "|" + String.join("//", pages == null ? List.of() : pages));
            inventory.insert(new ItemView("minecraft:written_book", 1, 1, Map.of()));
        }
        @Override public void giveStack(String itemId, int count, String snbt) {
            var data = snbt == null || snbt.isBlank() ? Map.<String, String>of() : Map.of("snbt", snbt);
            if (!inventory.insert(new ItemView(itemId, count, 64, data))) {
                spilled.add(itemId + " x" + count);
            }
        }
        @Override public void title(String titleKey, String subtitleKey, Object... args) {
            messages.add("TITLE:" + titleKey + "|" + subtitleKey);
        }
        @Override public boolean give(ItemSpec item) {
            return inventory.insert(new ItemView(item.id(), item.count(), 64, item.customData()));
        }
        @Override public boolean giveVerified(ItemSpec item) {
            if (failVerifiedCalls > 0) { failVerifiedCalls--; return false; }
            if (!inventory.canReceive(List.of(item))) return false;
            return give(item);
        }
        @Override public InventoryView inventory() { return inventory; }
        @Override public ItemView mainHand() {
            return selectedSlot >= 0 && selectedSlot < inventory.slots() ? inventory.stackAt(selectedSlot) : ItemView.EMPTY;
        }
        @Override public PlayerGateway.BookCopyResult copyMainHandBook() {
            ItemView held = mainHand();
            if (held.isEmpty() || switch (held.id()) {
                case "minecraft:book", "minecraft:writable_book", "minecraft:written_book",
                        "minecraft:enchanted_book", "minecraft:knowledge_book" -> false;
                default -> true;
            }) return PlayerGateway.BookCopyResult.NOT_A_BOOK;
            return inventory.insert(held.withCount(1))
                    ? PlayerGateway.BookCopyResult.COPIED
                    : PlayerGateway.BookCopyResult.NO_SPACE;
        }
        @Override public int selectedSlot() { return selectedSlot; }
        @Override public void selectSlot(int slot) { selectedSlot = slot; }
        @Override public void applyEffect(String effectId, int durationTicks, int amplifier) {
            effects.add(effectId + ":" + durationTicks + ":" + amplifier);
        }
        @Override public void closeMenu() {}
        @Override public void teleport(String dim, double tx, double ty, double tz) {
            dimension = dim; x = tx; y = ty; z = tz;
        }
        @Override public void teleport(String dim, double tx, double ty, double tz,
                                       float yaw, float pitch) {
            dimension = dim; x = tx; y = ty; z = tz; lastTeleportYaw = yaw;
        }
        @Override public void setVelocity(double nvx, double nvy, double nvz) {
            vx = nvx; vy = nvy; vz = nvz;
        }
        @Override public boolean ridingBoatLike() { return ridingBoat; }
        @Override public boolean startRiding(UUID vehicle) {
            TestPlayer carrier = server == null || vehicle == null ? null : server.players.get(vehicle);
            if (carrier == null || carrier == this || carrier.passengerUuid != null) return false;
            vehicleUuid = vehicle;
            carrier.passengerUuid = uuid;
            return true;
        }
        @Override public void stopRiding() {
            TestPlayer carrier = server == null || vehicleUuid == null ? null : server.players.get(vehicleUuid);
            if (carrier != null && uuid.equals(carrier.passengerUuid)) carrier.passengerUuid = null;
            vehicleUuid = null;
        }
        @Override public boolean isPassenger() { return vehicleUuid != null; }
        @Override public boolean isPassengerOf(UUID vehicle) { return vehicle != null && vehicle.equals(vehicleUuid); }
        @Override public boolean hasPassenger(UUID passenger) {
            return passenger != null && passenger.equals(passengerUuid);
        }
        public String lastMessage() { return messages.isEmpty() ? "" : messages.get(messages.size() - 1); }
        public boolean told(String fragment) {
            return messages.stream().anyMatch(m -> m.contains(fragment));
        }
    }

    // ---------------------------------------------------------------- server

    public static final class TestServer implements ServerGateway {
        public final Map<UUID, TestPlayer> players = new LinkedHashMap<>();
        public long tick;

        public TestPlayer add(String name) {
            TestPlayer player = new TestPlayer(name, 36);
            player.server = this;
            players.put(player.uuid, player);
            return player;
        }

        public TestPlayer byName(String name) {
            return players.values().stream().filter(p -> p.name.equalsIgnoreCase(name)).findFirst().orElse(null);
        }

        @Override public List<PlayerGateway> onlinePlayers() {
            return players.values().stream().filter(p -> p.online).map(p -> (PlayerGateway) p).toList();
        }

        @Override public PlayerGateway findPlayer(String nameOrUuid) {
            if (nameOrUuid == null) return null;
            for (TestPlayer p : players.values()) {
                if (p.online && (p.name.equalsIgnoreCase(nameOrUuid) || p.uuid.toString().equals(nameOrUuid))) return p;
            }
            return null;
        }

        @Override public long tickCount() { return tick; }
    }

    // ---------------------------------------------------------------- storage

    /** In-memory world containers: 27-slot chests with merge-then-fill inserts. */
    public static final class TestContainers implements WorldContainerGateway {
        public final Map<String, List<ItemView>> slots = new HashMap<>();
        public final List<String> drops = new ArrayList<>();
        public int slotCount = 27;
        public int maxStack = 64;

        private static String key(String dim, int x, int y, int z) {
            return dim + "|" + x + "," + y + "," + z;
        }

        public void placeContainer(String dim, int x, int y, int z) {
            slots.computeIfAbsent(key(dim, x, y, z), k -> new ArrayList<>(
                    java.util.Collections.nCopies(slotCount, ItemView.EMPTY)));
        }

        public void put(String dim, int x, int y, int z, String itemId, int count) {
            placeContainer(dim, x, y, z);
            insert(dim, x, y, z, itemId, count);
        }

        @Override public boolean isContainer(String dim, int x, int y, int z) {
            return slots.containsKey(key(dim, x, y, z));
        }

        @Override public int countUnits(String dim, int x, int y, int z, Map<String, Integer> unitValues) {
            List<ItemView> c = slots.get(key(dim, x, y, z));
            if (c == null) return -1;
            int total = 0;
            for (ItemView v : c) {
                if (!v.isEmpty()) total += unitValues.getOrDefault(v.id(), 0) * v.count();
            }
            return total;
        }

        @Override public int insert(String dim, int x, int y, int z, String itemId, int count) {
            List<ItemView> c = slots.get(key(dim, x, y, z));
            if (c == null || count <= 0) return count;
            int remaining = count;
            for (int i = 0; i < c.size() && remaining > 0; i++) {
                ItemView cur = c.get(i);
                if (cur.isEmpty() || !cur.id().equals(itemId) || cur.count() >= maxStack) continue;
                int move = Math.min(remaining, maxStack - cur.count());
                c.set(i, cur.withCount(cur.count() + move));
                remaining -= move;
            }
            for (int i = 0; i < c.size() && remaining > 0; i++) {
                if (!c.get(i).isEmpty()) continue;
                int move = Math.min(remaining, maxStack);
                c.set(i, new ItemView(itemId, move, maxStack, Map.of()));
                remaining -= move;
            }
            return remaining;
        }

        @Override public void dropItem(String dim, int x, int y, int z, String itemId, int count) {
            drops.add(key(dim, x, y, z) + " " + itemId + " x" + count);
        }

        @Override public ContainerCapacity capacity(String dim, int x, int y, int z) {
            List<ItemView> c = slots.get(key(dim, x, y, z));
            if (c == null) return null;
            int empty = 0;
            Map<String, Integer> room = new HashMap<>();
            for (ItemView v : c) {
                if (v.isEmpty()) { empty++; continue; }
                int head = Math.max(0, v.maxStackSize() - v.count());
                if (head > 0) room.merge(v.id(), head, Integer::sum);
            }
            return new ContainerCapacity(empty, room);
        }

        @Override public int stackLimit(String itemId) { return maxStack; }

        @Override public int remove(String dim, int x, int y, int z, String itemId, int count) {
            List<ItemView> c = slots.get(key(dim, x, y, z));
            if (c == null || count <= 0) return 0;
            int removed = 0;
            for (int i = 0; i < c.size() && removed < count; i++) {
                ItemView cur = c.get(i);
                if (cur.isEmpty() || !cur.id().equals(itemId)) continue;
                int take = Math.min(cur.count(), count - removed);
                c.set(i, take == cur.count() ? ItemView.EMPTY : cur.withCount(cur.count() - take));
                removed += take;
            }
            return removed;
        }

        /** SNBT survives the trip: slots carry it in customData so drain returns it. */
        @Override public int insertStack(String dim, int x, int y, int z,
                                         String itemId, int count, String snbt) {
            List<ItemView> c = slots.get(key(dim, x, y, z));
            if (c == null || count <= 0) return count;
            int remaining = count;
            var data = snbt == null || snbt.isBlank() ? Map.<String, String>of() : Map.of("snbt", snbt);
            for (int i = 0; i < c.size() && remaining > 0; i++) {
                ItemView cur = c.get(i);
                if (cur.isEmpty() || !cur.id().equals(itemId) || cur.count() >= maxStack
                        || !cur.customData().equals(data)) continue;
                int move = Math.min(remaining, maxStack - cur.count());
                c.set(i, cur.withCount(cur.count() + move));
                remaining -= move;
            }
            for (int i = 0; i < c.size() && remaining > 0; i++) {
                if (!c.get(i).isEmpty()) continue;
                int move = Math.min(remaining, maxStack);
                c.set(i, new ItemView(itemId, move, maxStack, data));
                remaining -= move;
            }
            return remaining;
        }

        @Override public List<com.dwurdy.straja.domain.model.SeizedStack> drain(
                String dim, int x, int y, int z) {
            List<ItemView> c = slots.get(key(dim, x, y, z));
            if (c == null) return List.of();
            var out = new ArrayList<com.dwurdy.straja.domain.model.SeizedStack>();
            for (int i = 0; i < c.size(); i++) {
                ItemView v = c.get(i);
                if (v == null || v.isEmpty()) continue;
                String snbt = v.customData() == null ? null : v.customData().get("snbt");
                out.add(new com.dwurdy.straja.domain.model.SeizedStack(
                        "slot:" + i, v.id(), v.count(), snbt, List.of()));
                c.set(i, ItemView.EMPTY);
            }
            return out;
        }
    }

    /** In-memory guard-NPC bridge: faction points, quest log, positioned guards. */
    public static final class TestNpcGuards implements NpcGuardGateway {
        public static final class Guard {
            public String dimension = "minecraft:overworld";
            public int factionId;
            public double x, y, z;
            public int aggroRange = 0;
            public UUID target;
            public boolean dead;
            public final Set<UUID> lineOfSight = new HashSet<>();
        }

        public boolean available = true;
        public final Map<UUID, Guard> guards = new LinkedHashMap<>();
        public final Map<String, Map<Integer, Integer>> factionPoints = new HashMap<>();
        public final List<String> questLog = new ArrayList<>();
        public final List<String> commands = new ArrayList<>();

        public UUID addGuard(double x, double y, double z, int factionId) {
            Guard g = new Guard();
            g.x = x; g.y = y; g.z = z; g.factionId = factionId;
            UUID id = UUID.randomUUID();
            guards.put(id, g);
            return id;
        }

        public UUID lastGuardId() {
            return guards.isEmpty() ? null : new ArrayList<>(guards.keySet()).get(guards.size() - 1);
        }

        @Override public boolean available() { return available; }
        @Override public Integer factionPoints(UUID playerId, int factionId) {
            return factionPoints.getOrDefault(playerId.toString(), Map.of()).get(factionId);
        }
        @Override public void setFactionPoints(UUID playerId, int factionId, int points) {
            factionPoints.computeIfAbsent(playerId.toString(), k -> new HashMap<>()).put(factionId, points);
        }
        @Override public void startQuestForTeam(String teamName, int questId) {
            questLog.add("start-team:" + teamName + ":" + questId);
        }
        @Override public void startQuestForPlayer(UUID playerId, int questId) {
            questLog.add("start-player:" + playerId + ":" + questId);
        }
        @Override public void finishQuestForTeam(String teamName, int questId) {
            questLog.add("finish-team:" + teamName + ":" + questId);
        }
        @Override public List<GuardRef> guardsNear(String dim, double x, double y, double z,
                double radius, int factionId) {
            var out = new ArrayList<GuardRef>();
            guards.forEach((id, g) -> {
                if (g.dead || g.factionId != factionId || !dim.equals(g.dimension)) return;
                double dx = g.x - x, dy = g.y - y, dz = g.z - z;
                if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                    out.add(new GuardRef(id, g.x, g.y, g.z));
                }
            });
            return out;
        }
        @Override public boolean hasLineOfSight(UUID guardId, UUID playerId) {
            Guard g = guards.get(guardId);
            return g != null && g.lineOfSight.contains(playerId);
        }
        @Override public int aggroRange(UUID guardId, int fallback) {
            Guard g = guards.get(guardId);
            return g != null && g.aggroRange > 0 ? g.aggroRange : fallback;
        }
        @Override public void setTarget(UUID guardId, UUID playerId) {
            Guard g = guards.get(guardId);
            if (g != null) g.target = playerId;
        }
        @Override public void clearTargetIfTargeting(UUID guardId, UUID playerId) {
            Guard g = guards.get(guardId);
            if (g != null && playerId != null && playerId.equals(g.target)) g.target = null;
        }
    }

    // ---------------------------------------------------------------- currency

    public static final class TestCurrency implements CurrencyProvider {
        public boolean available = true;
        public int balance;
        public final List<String> receipts = new ArrayList<>();
        public int depositCalls;
        public int lastAmount;
        public boolean returnPartialDeposit;

        @Override public String name() { return "TestCoins"; }
        @Override public boolean available() { return available; }
        @Override public Map<Integer, String> denominations() {
            return Map.of(1, "test:bronze", 10, "test:brass", 100, "test:silver", 1000, "test:gold");
        }
        @Override public int balanceOf(PlayerGateway player) { return balance; }
        @Override public Withdrawal withdraw(PlayerGateway player, int amount) {
            if (balance < amount) return Withdrawal.failed("insufficient_funds");
            balance -= amount;
            return Withdrawal.ok(amount);
        }
        @Override public Deposit deposit(PlayerGateway player, int amount, String payoutId) {
            depositCalls++;
            lastAmount = amount;
            if (returnPartialDeposit) return new Deposit(false, 1, "delivery_failed_at_denomination");
            if (payoutId != null) {
                if (receipts.contains(payoutId)) return Deposit.ok(0);
                receipts.add(payoutId);
            }
            balance += amount;
            return Deposit.ok(1);
        }
        @Override public boolean hasReceipt(PlayerGateway player, String payoutId) { return receipts.contains(payoutId); }
    }

    public static final class TestDelivery implements DeliveryProvider {
        public boolean available = true;
        public final List<String> sent = new ArrayList<>();
        @Override public boolean available() { return available; }
        @Override public Outcome sendLetter(PlayerGateway sender, String to, String subject, String body) {
            if (!available) return Outcome.failed("unavailable");
            sent.add(to + "|" + subject + "|" + body);
            return Outcome.delivered();
        }
        @Override public Outcome sendPackage(PlayerGateway sender, String to, List<ItemSpec> contents, String label) {
            if (!available) return Outcome.failed("unavailable");
            sent.add(to + "|pkg|" + contents.size());
            return Outcome.delivered();
        }
    }

    // ---------------------------------------------------------------- world

    public static final class TestWorld implements WorldGateway {
        /** Blocks keyed by "dim,x,y,z"; absent = air. */
        public final Map<String, BlockInfo> blocks = new HashMap<>();
        public final List<String> signs = new ArrayList<>();

        private static String key(String dim, int x, int y, int z) {
            return dim + "," + x + "," + y + "," + z;
        }

        public void set(String dim, int x, int y, int z, BlockInfo block) {
            blocks.put(key(dim, x, y, z), block);
        }

        /** A solid box shell with a two-block door gap at (dx,dy..dy+1,dz). */
        public void room(String dim, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                         int dx, int dy, int dz) {
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++) {
                        boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                        if (!boundary) continue;
                        if (x == dx && z == dz && (y == dy || y == dy + 1)) {
                            set(dim, x, y, z, new BlockInfo("minecraft:oak_door", false, true, false));
                        } else {
                            set(dim, x, y, z, new BlockInfo("minecraft:stone", false, false, true));
                        }
                    }
        }

        @Override public BlockInfo blockAt(String dimension, int x, int y, int z) {
            return blocks.getOrDefault(key(dimension, x, y, z), new BlockInfo("minecraft:air", true, false, false));
        }

        @Override public boolean setRoomSign(String dimension, int x, int y, int z, String facing,
                                           String line1, String line2, String line3) {
            signs.add(x + "," + y + "," + z + "|" + line1 + "|" + line2 + "|" + line3);
            return true;
        }

        /** Door positions closed via WorldGateway.closeDoor — "dim,x,y,z". */
        public final List<String> closedDoors = new ArrayList<>();
        /** Sounds played via playSoundAt — "dim,x,y,z|id". */
        public final List<String> sounds = new ArrayList<>();

        @Override public void closeDoor(String dimension, int x, int y, int z) {
            closedDoors.add(key(dimension, x, y, z));
        }

        @Override public void playSoundAt(String dimension, double x, double y, double z,
                                          double radius, String soundId) {
            sounds.add(dimension + "," + (int) x + "," + (int) y + "," + (int) z + "|" + soundId);
        }
    }

    // ---------------------------------------------------------------- factions

    /** In-memory scoreboard teams keyed by player UUID; teams exist once joined. */
    public static final class TestFactions implements FactionGateway {
        public final Map<UUID, String> memberships = new HashMap<>();
        public final java.util.Set<String> teams = new java.util.LinkedHashSet<>();

        @Override public String teamOf(PlayerGateway player) {
            return memberships.get(player.uuid());
        }

        @Override public void joinTeam(PlayerGateway player, String teamName) {
            teams.add(teamName);
            memberships.put(player.uuid(), teamName);
        }

        @Override public void leaveTeam(PlayerGateway player) {
            memberships.remove(player.uuid());
        }

        @Override public boolean teamExists(String teamName) {
            return teamName != null && teams.contains(teamName);
        }
    }

    // ---------------------------------------------------------------- context

    /** Full context backed by in-memory SavedData-equivalent stores. */
    public static StrajaContext context(TestServer server, FixedClock clock) {
        StrajaPolicies policies = new StrajaPolicies();
        return context(server, clock, policies);
    }

    public static StrajaContext context(TestServer server, FixedClock clock, StrajaPolicies policies) {
        Map<String, MemoryStore> memory = new HashMap<>();
        StoreAccess access = name -> memory.computeIfAbsent(name, k -> new MemoryStore());
        TestCurrency currency = new TestCurrency();
        TestDelivery delivery = new TestDelivery();
        return new StrajaContext(
                policies, clock, new SeqIds(), server,
                new SavedPlayerStateRepository(access),
                new SavedStores.Setup(access),
                new SavedStores.Audit(access, policies.auditRetentionLimit),
                new SavedStores.Inbox(access),
                new SavedStores.Missions(access),
                new SavedStores.Fines(access),
                new SavedStores.Prison(access),
                new SavedStores.Rooms(access),
                new SavedStores.Complaints(access),
                new SavedStores.Reports(access),
                new SavedStores.Audiences(access),
                new SavedStores.Emergency(access),
                new SavedStores.MissionTemplates(access),
                new SavedStores.Custody(access),
                new SavedStores.Archive(access),
                new SavedStores.Npcs(access),
                new SavedStores.Test(access),
                currency, delivery, new TestWorld(), new TestFactions(),
                new SavedStores.AdminTools(access),
                new SavedStores.IdentityCards(access),
                new SavedStores.Incidents(access),
                new SavedStores.Bolos(access),
                new SavedStores.Evidence(access),
                new SavedStores.ArrestRecords(access),
                new SavedStores.Reputation(access),
                new SavedStores.Storage(access),
                new SavedStores.LawCheckpoints(access),
                new SavedStores.InspectionLedger(access),
                new SavedStores.PrisonerRegister(access),
                new SavedStores.LaborCamps(access),
                new SavedStores.MerchantDesks(access),
                new TestContainers(),
                new TestNpcGuards(),
                new TestDeepScan());
    }

    /** Returns the inventory snapshot staged for the scanned player. */
    public static final class TestDeepScan implements com.dwurdy.straja.application.port.out.DeepScanGateway {
        public final java.util.Map<java.util.UUID, java.util.List<com.dwurdy.straja.domain.model.SnapshotItem>>
                inventories = new java.util.HashMap<>();
        /** Optional server ref — seizeAll drains the real TestInventory. */
        public TestServer server;

        @Override
        public java.util.List<com.dwurdy.straja.domain.model.SnapshotItem> deepScan(java.util.UUID playerUuid) {
            return new java.util.ArrayList<>(
                    inventories.getOrDefault(playerUuid, java.util.List.of()));
        }

        /** M4: physically empties every carried slot; SNBT/contained ids ride customData. */
        @Override
        public java.util.List<com.dwurdy.straja.domain.model.SeizedStack> seizeAll(java.util.UUID playerUuid) {
            var p = server == null ? null : server.players.get(playerUuid);
            if (p == null) return java.util.List.of();
            var out = new java.util.ArrayList<com.dwurdy.straja.domain.model.SeizedStack>();
            for (int i = 0; i < p.inventory.slots.size(); i++) {
                var v = p.inventory.slots.get(i);
                if (v == null || v.isEmpty()) continue;
                var data = v.customData();
                String snbt = data == null ? null : data.get("snbt");
                String contains = data == null ? null : data.get("contains");
                java.util.List<String> containedIds = contains == null || contains.isBlank()
                        ? java.util.List.of() : java.util.List.of(contains.split(","));
                out.add(new com.dwurdy.straja.domain.model.SeizedStack(
                        "main:" + i, v.id(), v.count(), snbt, containedIds));
                p.inventory.slots.set(i, ItemView.EMPTY);
            }
            return out;
        }
    }

    public static StrajaPolicies policies() {
        StrajaPolicies p = new StrajaPolicies();
        p.testCommandsEnabled = true;
        return p;
    }
}
