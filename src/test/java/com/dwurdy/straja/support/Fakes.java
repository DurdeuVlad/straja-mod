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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        public final UUID uuid;
        public String name;
        public boolean online = true;
        public boolean op;
        public String dimension = "minecraft:overworld";
        public double x, y, z;
        public double health = 20, maxHealth = 20, absorption;
        public final TestInventory inventory;
        public final List<String> messages = new ArrayList<>();
        public int selectedSlot = -1;
        public final List<String> effects = new ArrayList<>();
        /** When > 0, the next N giveVerified calls fail as if delivery broke. */
        public int failVerifiedCalls;

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
        @Override public int selectedSlot() { return selectedSlot; }
        @Override public void selectSlot(int slot) { selectedSlot = slot; }
        @Override public void applyEffect(String effectId, int durationTicks, int amplifier) {
            effects.add(effectId + ":" + durationTicks + ":" + amplifier);
        }
        @Override public void closeMenu() {}
        @Override public void teleport(String dim, double tx, double ty, double tz) {
            dimension = dim; x = tx; y = ty; z = tz;
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

    // ---------------------------------------------------------------- currency

    public static final class TestCurrency implements CurrencyProvider {
        public boolean available = true;
        public int balance;
        public final List<String> receipts = new ArrayList<>();
        public int depositCalls;
        public int lastAmount;

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
                new SavedStores.MissionTemplates(access),
                new SavedStores.Custody(access),
                new SavedStores.Archive(access),
                new SavedStores.Npcs(access),
                new SavedStores.Test(access),
                currency, delivery, new TestWorld(), new TestFactions());
    }

    public static StrajaPolicies policies() {
        StrajaPolicies p = new StrajaPolicies();
        p.testCommandsEnabled = true;
        return p;
    }
}
