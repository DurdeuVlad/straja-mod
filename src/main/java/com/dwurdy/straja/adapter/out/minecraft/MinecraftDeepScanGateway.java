package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.DeepScanGateway;
import com.dwurdy.straja.domain.model.SeizedStack;
import com.dwurdy.straja.domain.model.SnapshotItem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Live deep-scan: ports the prototype's {@code cpSubStacks}/{@code cpDeepScan}/
 * {@code cpScanContraband} traversal. Every non-empty stack (inventory, armor,
 * offhand, nested container items to depth 3, plus serialized-NBT fallback
 * hits that enumerable APIs miss) becomes a {@link SnapshotItem} with a slot
 * path like {@code main:4>2} or {@code offhand:0>deep}.
 */
public final class MinecraftDeepScanGateway implements DeepScanGateway {
    private static final int MAX_DEPTH = 3;
    private static final int MAX_TAG_DEPTH = 8;
    private final MinecraftServer server;

    public MinecraftDeepScanGateway(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public List<SnapshotItem> deepScan(UUID playerUuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player == null) return List.of();
        return scanInventory(player);
    }

    /** Scans an already-resolved player — the GameTest seam for live traversal proof. */
    public List<SnapshotItem> scanInventory(ServerPlayer player) {
        List<SnapshotItem> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            scan(inv.getItem(i), slotLabel(i), 0, out);
        }
        scanCurios(player, out);
        return out;
    }

    /**
     * M4 physical seizure ({@code cpSeizeAll} parity): every top-level stack —
     * main+hotbar+armor+offhand plus equipped Curios when the mod is present —
     * is emptied from its slot and returned with full SNBT (nested contents
     * travel inside it) and the set of item ids reachable inside.
     */
    @Override
    public List<SeizedStack> seizeAll(UUID playerUuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player == null) return List.of();
        return seizeInventory(player);
    }

    /** Resolved-player seizure — the GameTest seam mirroring {@link #scanInventory}. */
    public List<SeizedStack> seizeInventory(ServerPlayer player) {
        List<SeizedStack> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            out.add(seized(slotLabel(i), stack));
            inv.setItem(i, ItemStack.EMPTY);
        }
        inv.setChanged();
        seizeCurios(player, out);
        return out;
    }

    /** Reflective Curios extraction — mirrors {@link #scanCurios}; failures mean no extra slots. */
    private void seizeCurios(ServerPlayer player, List<SeizedStack> out) {
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("curios")) return;
            var api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object opt = api.getMethod("getCuriosInventory",
                    net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
            if (!(opt instanceof java.util.Optional<?> present) || present.isEmpty()) return;
            Object equipped = present.get().getClass()
                    .getMethod("getEquippedCurios").invoke(present.get());
            int slots = (int) equipped.getClass().getMethod("getSlots").invoke(equipped);
            var empty = ItemStack.EMPTY;
            for (int i = 0; i < slots; i++) {
                Object stack = equipped.getClass()
                        .getMethod("getStackInSlot", int.class).invoke(equipped, i);
                if (stack instanceof ItemStack s && !s.isEmpty()) {
                    out.add(seized("curios:" + i, s));
                    equipped.getClass().getMethod("setStackInSlot",
                            int.class, ItemStack.class).invoke(equipped, i, empty);
                }
            }
        } catch (Throwable ignored) {
            // Curios absent or API drift — vanilla seizure already covered the player
        }
    }

    private SeizedStack seized(String slotPath, ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String snbt = componentsTag(stack);
        List<String> contained = new ArrayList<>();
        List<SnapshotItem> nested = new ArrayList<>();
        for (ItemStack sub : subStacks(stack)) {
            scan(sub, slotPath + ">", 1, nested);
        }
        for (SnapshotItem row : nested) contained.add(row.itemId);
        return new SeizedStack(slotPath, id, stack.getCount(), snbt, contained);
    }

    /**
     * Optional Curios support (prototype cpScanContraband scanned equipped
     * Curios slots). Purely reflective — Curios is not a compile dependency;
     * any failure just means "no extra slots".
     */
    private void scanCurios(ServerPlayer player, List<SnapshotItem> out) {
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("curios")) return;
            var api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object opt = api.getMethod("getCuriosInventory",
                    net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
            if (!(opt instanceof java.util.Optional<?> present) || present.isEmpty()) return;
            Object equipped = present.get().getClass()
                    .getMethod("getEquippedCurios").invoke(present.get());
            int slots = (int) equipped.getClass().getMethod("getSlots").invoke(equipped);
            for (int i = 0; i < slots; i++) {
                Object stack = equipped.getClass()
                        .getMethod("getStackInSlot", int.class).invoke(equipped, i);
                if (stack instanceof ItemStack s) scan(s, "curios:" + i, 0, out);
            }
        } catch (Throwable ignored) {
            // Curios absent or API drift — vanilla scan already covered the player
        }
    }

    private static String slotLabel(int index) {
        // Inventory layout: 0-35 main (0-8 hotbar), 36-39 armor, 40 offhand.
        if (index < 36) return "main:" + index;
        if (index < 40) return "armor:" + (index - 36);
        return "offhand:" + (index - 40);
    }

    private void scan(ItemStack stack, String slotPath, int depth, List<SnapshotItem> out) {
        if (stack == null || stack.isEmpty() || depth > MAX_DEPTH) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        out.add(new SnapshotItem(slotPath, id, stack.getCount(),
                componentsTag(stack), stack.getHoverName().getString()));
        List<ItemStack> subs = subStacks(stack);
        if (subs.isEmpty()) {
            // No enumerable contents — structural NBT walk over the components
            // subtree so mod-specific storage can't hide contraband (prototype
            // cpDeepScan). Hits surface as snapshot rows under ">deep".
            deepScanTag(stack, slotPath, out);
            return;
        }
        for (int i = 0; i < subs.size(); i++) {
            scan(subs.get(i), slotPath + ">" + i, depth + 1, out);
        }
    }

    /** Container enumeration order mirrors the prototype: CONTAINER + BUNDLE first, capability only when empty. */
    private static List<ItemStack> subStacks(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        var container = stack.get(DataComponents.CONTAINER);
        if (container != null) container.nonEmptyItems().forEach(out::add);
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) bundle.items().forEach(out::add);
        if (!out.isEmpty()) return out;
        try {
            IItemHandler cap = stack.getCapability(Capabilities.ItemHandler.ITEM);
            if (cap != null) {
                for (int i = 0; i < cap.getSlots(); i++) {
                    ItemStack sub = cap.getStackInSlot(i);
                    if (!sub.isEmpty()) out.add(sub);
                }
            }
        } catch (RuntimeException ignored) {
            // no item handler — plain stack
        }
        return out;
    }

    private void deepScanTag(ItemStack stack, String slotPath, List<SnapshotItem> out) {
        try {
            Tag saved = stack.save(server.registryAccess());
            if (!(saved instanceof CompoundTag root)) return;
            walkTag(root.getCompound("components"), slotPath, 0, out);
        } catch (RuntimeException ignored) {
            // serialization impossible — plain stack already recorded
        }
    }

    /** Structural walk: item records = compound with 'id'/'item'/'Item' + numeric count. */
    private static void walkTag(Tag tag, String slotPath, int depth, List<SnapshotItem> out) {
        if (tag == null || depth > MAX_TAG_DEPTH) return;
        if (tag instanceof CompoundTag compound) {
            String id = compound.getString("id");
            if (id.isEmpty()) id = compound.getString("item");
            if (id.isEmpty()) id = compound.getString("Item");
            String countKey = compound.contains("count") ? "count"
                    : (compound.contains("Count") ? "Count" : null);
            if (!id.isEmpty() && countKey != null && isNumericTag(compound.getTagType(countKey))) {
                out.add(new SnapshotItem(slotPath + ">deep", id, compound.getInt(countKey),
                        null, id + " (în container)"));
            }
            for (String key : compound.getAllKeys()) {
                walkTag(compound.get(key), slotPath, depth + 1, out);
            }
            return;
        }
        if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) walkTag(list.get(i), slotPath, depth + 1, out);
        }
    }

    private static boolean isNumericTag(byte type) {
        return type >= Tag.TAG_BYTE && type <= Tag.TAG_DOUBLE;
    }

    private String componentsTag(ItemStack stack) {
        try {
            Tag saved = stack.save(server.registryAccess());
            return saved instanceof CompoundTag tag ? tag.toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
