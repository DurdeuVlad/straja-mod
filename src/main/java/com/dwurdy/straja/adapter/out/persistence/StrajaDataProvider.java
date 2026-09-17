package com.dwurdy.straja.adapter.out.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Central access point for all Straja SavedData stores. Each domain owns one
 * store under world/data/straja_<name>.dat, mirroring the KubeJS persistentData
 * keys so migrations stay traceable.
 */
public final class StrajaDataProvider {

    /**
     * Pure snapshot policy kept Minecraft-free so unit tests can load it
     * without the platform classes used by the provider itself.
     */
    static final class BackupPolicy {
        static final int RETENTION = 10;
        static final List<String> SOURCE_STORES = List.of(
                "setup", "audit", "inbox", "missions", "fines", "prisons", "rooms",
                "complaints", "custody", "archive", "identity_cards", "npcs", "test", "players");

        private BackupPolicy() {}
    }

    private StrajaDataProvider() {}

    public static JsonStore get(MinecraftServer server, String name) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(JsonStore.factory(), "straja_" + name);
    }

    public static CompoundTag data(MinecraftServer server, String name) {
        return get(server, name).data();
    }

    public static CompoundTag edit(MinecraftServer server, String name) {
        return get(server, name).edit();
    }

    public static void save(MinecraftServer server, String name) {
        get(server, name).setDirty();
    }

    /**
     * Creates a durable, bounded snapshot of every Straja store.
     *
     * <p>The snapshot lives in its own SavedData store, so an administrator can
     * request a backup without touching the world filesystem or interrupting
     * normal persistence. The backup store is deliberately excluded from its
     * own source list to avoid recursive growth.</p>
     */
    public static BackupResult createBackup(MinecraftServer server) {
        long createdAt = System.currentTimeMillis();
        String id = createdAt + "-" + UUID.randomUUID().toString().substring(0, 8);
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("snapshot_id", id);
        snapshot.putLong("created_at", createdAt);

        for (String storeName : BackupPolicy.SOURCE_STORES) {
            snapshot.put(storeName, get(server, storeName).data().copy());
        }

        CompoundTag backups = edit(server, "backup");
        backups.put("snapshot_" + id, snapshot);
        pruneBackups(backups);
        return new BackupResult(id, BackupPolicy.SOURCE_STORES.size(), snapshotCount(backups));
    }

    private static void pruneBackups(CompoundTag backups) {
        List<String> snapshots = new ArrayList<>(backups.getAllKeys()).stream()
                .filter(key -> key.startsWith("snapshot_"))
                .sorted(Comparator.comparingLong(key -> backups.getCompound(key).getLong("created_at")))
                .toList();
        int removeCount = snapshots.size() - BackupPolicy.RETENTION;
        for (int i = 0; i < Math.max(0, removeCount); i++) {
            backups.remove(snapshots.get(i));
        }
    }

    private static int snapshotCount(CompoundTag backups) {
        return (int) backups.getAllKeys().stream()
                .filter(key -> key.startsWith("snapshot_"))
                .count();
    }

    public record BackupResult(String id, int storeCount, int retainedSnapshotCount) {}
}
