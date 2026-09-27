package com.dwurdy.straja.adapter.out.npc.customnpcs;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Minecraft-side bridge for GUI item icons.
 *
 * <p>Kept in its own class so the provider's bytecode signatures stay free of
 * Minecraft types: the unit-test JVM runs without Minecraft classes, and an
 * eager reference here would fail class verification for every test that
 * touches the provider. This class is only resolved when a GUI header icon is
 * actually rendered on a live server.</p>
 */
final class GuiItemIcons {
    private GuiItemIcons() {
    }

    /** Returns a vanilla item stack for the icon id, or null when unmapped. */
    static Object mcItemStack(String itemId) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        if (item == null || item == Items.AIR) {
            return null;
        }
        return new ItemStack(item);
    }
}
