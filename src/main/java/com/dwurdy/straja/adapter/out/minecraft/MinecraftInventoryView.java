package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/** Read/mutate view over a Player inventory. */
public class MinecraftInventoryView implements InventoryView {
    private final Inventory inventory;

    public MinecraftInventoryView(Inventory inventory) {
        this.inventory = inventory;
    }

    public static ItemStack build(ItemSpec spec) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(spec.id()));
        ItemStack stack = new ItemStack(item, Math.max(1, spec.count()));
        if (spec.displayName() != null) {
            stack.set(DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal(spec.displayName()));
        }
        if (!spec.customData().isEmpty()) {
            CompoundTag tag = new CompoundTag();
            spec.customData().forEach(tag::putString);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return stack;
    }

    public static ItemView view(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemView.EMPTY;
        Map<String, String> data = new LinkedHashMap<>();
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            CompoundTag tag = custom.copyTag();
            for (String key : tag.getAllKeys()) {
                Tag value = tag.get(key);
                if (value != null && value.getId() == Tag.TAG_STRING) {
                    data.put(key, tag.getString(key));
                }
            }
        }
        return new ItemView(stack.getItem().toString(), stack.getCount(),
                stack.getMaxStackSize(), data);
    }

    @Override public int slots() {
        return inventory.getContainerSize();
    }

    @Override public ItemView stackAt(int slot) {
        if (slot < 0 || slot >= slots()) return ItemView.EMPTY;
        return view(inventory.getItem(slot));
    }

    @Override public ItemView extract(int slot, int amount) {
        if (slot < 0 || slot >= slots() || amount <= 0) return ItemView.EMPTY;
        return view(inventory.removeItem(slot, amount));
    }

    /**
     * Simulated capacity check: merges into same-item slots first, then free
     * slots. Mirrors the reference inventoryCanReceiveBatch semantics. Only the
     * 36 main inventory slots participate — {@code Inventory.add} can never
     * place into armor or offhand.
     */
    @Override public boolean canReceive(List<ItemSpec> items) {
        int size = inventory.items.size();
        List<ItemStack> virtual = new ArrayList<>();
        for (int i = 0; i < size; i++) virtual.add(inventory.getItem(i).copy());
        for (ItemSpec spec : items) {
            int remaining = Math.max(1, spec.count());
            ItemStack probe = build(spec);
            int max = probe.getMaxStackSize();
            for (ItemStack slotStack : virtual) {
                if (remaining <= 0) break;
                if (slotStack.isEmpty() || slotStack.getCount() >= slotStack.getMaxStackSize()) continue;
                if (!ItemStack.isSameItemSameComponents(slotStack, probe)) continue;
                int room = slotStack.getMaxStackSize() - slotStack.getCount();
                int move = Math.min(remaining, room);
                slotStack.grow(move);
                remaining -= move;
            }
            for (int i = 0; i < size && remaining > 0; i++) {
                if (!virtual.get(i).isEmpty()) continue;
                int move = Math.min(remaining, max);
                ItemStack placed = probe.copy();
                placed.setCount(move);
                virtual.set(i, placed);
                remaining -= move;
            }
            if (remaining > 0) return false;
        }
        return true;
    }
}
