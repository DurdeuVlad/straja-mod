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

    /**
     * {@inheritDoc}
     * Rebuilds the stack from its serialized SNBT so component data (names,
     * container contents, enchantments) survives the trip into evidence.
     */
    @Override public int insertStack(String dimension, int x, int y, int z,
                                     String itemId, int count, String snbt) {
        Container container = containerAt(dimension, x, y, z);
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(itemId));
        if (item.isEmpty() || item.get() == net.minecraft.world.item.Items.AIR) return -1;
        if (container == null || count <= 0) return count;
        ItemStack template = stackFromSnbt(itemId, count, snbt, item.get());
        int remaining = count;
        // merge pass — component-aware so identical evidence stacks consolidate
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack cur = container.getItem(i);
            if (cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, template)
                    || cur.getCount() >= cur.getMaxStackSize()) continue;
            int move = Math.min(remaining, cur.getMaxStackSize() - cur.getCount());
            cur.grow(move);
            container.setItem(i, cur);
            remaining -= move;
        }
        // fill pass — fresh stacks keep the full component data
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (!container.getItem(i).isEmpty()) continue;
            ItemStack fresh = template.copyWithCount(Math.min(remaining, template.getMaxStackSize()));
            container.setItem(i, fresh);
            remaining -= fresh.getCount();
        }
        if (remaining < count) container.setChanged();
        return remaining;
    }

    /** {@inheritDoc} Counts vacant slots and per-item merge headroom. */
    @Override public ContainerCapacity capacity(String dimension, int x, int y, int z) {
        Container container = containerAt(dimension, x, y, z);
        if (container == null) return null;
        int empty = 0;
        Map<String, Integer> mergeRoom = new java.util.HashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack == null || stack.isEmpty()) {
                empty++;
                continue;
            }
            int room = stack.getMaxStackSize() - stack.getCount();
            // Only component-free stacks advertise merge room: insertStack
            // merges on isSameItemSameComponents, so a modified stack's
            // headroom is unusable for a plain incoming stack.
            if (room > 0 && stack.getComponentsPatch().isEmpty()) {
                mergeRoom.merge(BuiltInRegistries.ITEM.getKey(stack.getItem())
                        .toString(), room, Integer::sum);
            }
        }
        return new ContainerCapacity(empty, mergeRoom);
    }

    @Override public int stackLimit(String itemId) {
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(itemId));
        if (item.isEmpty() || item.get() == net.minecraft.world.item.Items.AIR) return -1;
        return item.get().getDefaultMaxStackSize();
    }

    /** {@inheritDoc} Pulls matching stacks out of the resolved container. */
    @Override public int remove(String dimension, int x, int y, int z, String itemId, int count) {
        Container container = containerAt(dimension, x, y, z);
        if (container == null || count <= 0) return 0;
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(itemId));
        if (item.isEmpty()) return 0;
        int removed = 0;
        for (int i = 0; i < container.getContainerSize() && removed < count; i++) {
            ItemStack stack = container.getItem(i);
            if (stack == null || stack.isEmpty() || stack.getItem() != item.get()) continue;
            int take = Math.min(stack.getCount(), count - removed);
            container.removeItem(i, take);
            removed += take;
        }
        container.setChanged();
        return removed;
    }

    /** {@inheritDoc} Empties the container; each stack keeps its full SNBT. */
    @Override public java.util.List<com.dwurdy.straja.domain.model.SeizedStack> drain(
            String dimension, int x, int y, int z) {
        Container container = containerAt(dimension, x, y, z);
        if (container == null) return java.util.List.of();
        var out = new java.util.ArrayList<com.dwurdy.straja.domain.model.SeizedStack>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String snbt;
            try {
                var tag = stack.save(server.registryAccess());
                snbt = tag instanceof net.minecraft.nbt.CompoundTag c ? c.toString() : null;
            } catch (RuntimeException ex) {
                snbt = null;
            }
            out.add(new com.dwurdy.straja.domain.model.SeizedStack(
                    "slot:" + i, id, stack.getCount(), snbt, java.util.List.of()));
            container.setItem(i, ItemStack.EMPTY);
        }
        container.setChanged();
        return out;
    }

    /** Rebuilds a stack from SNBT (ItemStack.parse); falls back to id+count. */
    private ItemStack stackFromSnbt(String itemId, int count, String snbt, Item fallback) {
        if (snbt != null && !snbt.isBlank()) {
            try {
                var tag = net.minecraft.nbt.TagParser.parseTag(snbt);
                var parsed = ItemStack.parse(server.registryAccess(), tag);
                if (parsed.isPresent() && !parsed.get().isEmpty()) return parsed.get();
            } catch (Exception ignored) {
                // malformed SNBT — plain stack below
            }
        }
        return new ItemStack(fallback, count);
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
