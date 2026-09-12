package com.dwurdy.straja.adapter.out.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

/**
 * A SavedData that holds a single CompoundTag payload. The dirty flag is the
 * persistence contract: every mutating repository call goes through
 * {@link #edit()} so restart safety is mechanical.
 */
public final class JsonStore extends SavedData {
    private CompoundTag data = new CompoundTag();

    public CompoundTag data() {
        return data;
    }

    /** Returns the mutable payload and marks the store dirty. */
    public CompoundTag edit() {
        setDirty();
        return data;
    }

    public void replace(CompoundTag next) {
        this.data = next == null ? new CompoundTag() : next;
        setDirty();
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider provider) {
        tag.put("straja", data);
        return tag;
    }

    public static Factory<JsonStore> factory() {
        return new Factory<>(JsonStore::new, (tag, provider) -> {
            JsonStore store = new JsonStore();
            store.data = tag.contains("straja") ? tag.getCompound("straja") : new CompoundTag();
            return store;
        });
    }
}
