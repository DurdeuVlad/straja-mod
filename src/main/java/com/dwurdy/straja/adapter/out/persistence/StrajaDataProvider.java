package com.dwurdy.straja.adapter.out.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Central access point for all Straja SavedData stores. Each domain owns one
 * store under world/data/straja_<name>.dat, mirroring the KubeJS persistentData
 * keys so migrations stay traceable.
 */
public final class StrajaDataProvider {
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
}
