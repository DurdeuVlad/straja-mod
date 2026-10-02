package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.WorldContainerGateway;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Vanilla {@link Container} access for the storage watch (chests, barrels, …). */
public final class MinecraftContainerGateway implements WorldContainerGateway {
    private final MinecraftServer server;

    public MinecraftContainerGateway(MinecraftServer server) {
        this.server = server;
    }

    private ServerLevel level(String dimension) {
        if (dimension == null || dimension.isBlank()) return server.overworld();
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private Container containerAt(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;
        var pos = new BlockPos(x, y, z);
        var state = level.getBlockState(pos);
        // ChestBlock.getContainer resolves the CompoundContainer so a double
        // chest is watched/deposited as one unit, not a silent half.
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chest) {
            return net.minecraft.world.level.block.ChestBlock.getContainer(chest, state, level, pos, false);
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    @Override public boolean isContainer(String dimension, int x, int y, int z) {
        return containerAt(dimension, x, y, z) != null;
    }

    @Override public int countUnits(String dimension, int x, int y, int z, Map<String, Integer> unitValues) {
        Container container = containerAt(dimension, x, y, z);
        if (container == null) return -1;
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            total += unitValues.getOrDefault(id, 0) * stack.getCount();
        }
        return total;
    }

    /**
     * {@inheritDoc}
     * Returns {@code -1} when the item id does not resolve (the defaulted
     * registry would otherwise silently hand back AIR).
     */
    @Override public int insert(String dimension, int x, int y, int z, String itemId, int count) {
        Container container = containerAt(dimension, x, y, z);
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(itemId));
        if (item.isEmpty() || item.get() == net.minecraft.world.item.Items.AIR) return -1;
        if (container == null || count <= 0) return count;
        Item resolved = item.get();
        int remaining = count;
        var template = new ItemStack(resolved);
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack cur = container.getItem(i);
            if (cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, template)
                    || cur.getCount() >= cur.getMaxStackSize()) continue;
            int move = Math.min(remaining, cur.getMaxStackSize() - cur.getCount());
            cur.grow(move);
            container.setItem(i, cur);
            remaining -= move;
        }
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (!container.getItem(i).isEmpty()) continue;
            ItemStack fresh = new ItemStack(resolved, Math.min(remaining, resolved.getDefaultMaxStackSize()));
            container.setItem(i, fresh);
            remaining -= fresh.getCount();
        }
        if (remaining < count) container.setChanged();
        return remaining;
    }

    @Override public void dropItem(String dimension, int x, int y, int z, String itemId, int count) {
        ServerLevel level = level(dimension);
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(itemId));
        if (level == null || item.isEmpty() || item.get() == net.minecraft.world.item.Items.AIR || count <= 0) return;
        var entity = new ItemEntity(level, x + 0.5, y + 1.0, z + 0.5, new ItemStack(item.get(), count));
        level.addFreshEntity(entity);
    }

    @Override public String canonicalKey(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return WorldContainerGateway.super.canonicalKey(dimension, x, y, z);
        var pos = new BlockPos(x, y, z);
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            var partner = pos.relative(
                    net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            // Canonical half = lexicographically smaller position, so either
            // half's click or watch resolves to the same key.
            var canon = pos.compareTo(partner) <= 0 ? pos : partner;
            return dimension + "|" + canon.getX() + "," + canon.getY() + "," + canon.getZ();
        }
        return WorldContainerGateway.super.canonicalKey(dimension, x, y, z);
    }
}
