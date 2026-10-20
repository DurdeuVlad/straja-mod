package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.adapter.out.npc.customnpcs.GuiItemIcons;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * ItemStack builder for {@link DeskIcon} view values — the only place menu
 * icons touch the Minecraft registry. A missing/air item id falls back to
 * paper; long names and lore are bounded. Never throws.
 */
final class DeskIcons {
    private static final int MAX_NAME = 80;
    private static final int MAX_LORE_LINES = 16;
    private static final int MAX_LORE_LINE = 160;

    private DeskIcons() {}

    /** Builds the client-safe stack for an icon; empty only if even paper is unmapped. */
    static ItemStack stack(DeskIcon icon) {
        if (icon == null) icon = new DeskIcon(null, "", List.of());
        ItemStack stack = stackOrEmpty(icon.itemId());
        if (stack.isEmpty()) stack = stackOrEmpty(DeskIcon.FALLBACK_ITEM);
        if (stack.isEmpty()) return stack;
        String name = icon.name().length() > MAX_NAME
                ? icon.name().substring(0, MAX_NAME) : icon.name();
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        List<Component> lines = icon.lore().stream().limit(MAX_LORE_LINES)
                .<Component>map(line -> Component.literal(line.length() > MAX_LORE_LINE
                        ? line.substring(0, MAX_LORE_LINE) : line))
                .toList();
        stack.set(DataComponents.LORE, new ItemLore(lines));
        return stack;
    }

    private static ItemStack stackOrEmpty(String itemId) {
        try {
            return GuiItemIcons.mcItemStack(itemId) instanceof ItemStack stack
                    ? stack : ItemStack.EMPTY;
        } catch (RuntimeException malformed) {
            return ItemStack.EMPTY;
        }
    }
}
