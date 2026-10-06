package com.dwurdy.straja.adapter.in.item;

import com.dwurdy.straja.adapter.in.crafting.ForgeCrafting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * #247 — document-class items that double as forge results. Resolution is
 * keyed on the {@code StrajaForgePending} marker written by
 * {@link com.dwurdy.straja.adapter.in.crafting.SmithingForgeRecipe}, never
 * on the call site — {@code onCraftedBy} covers normal takes and
 * {@code inventoryTick} covers shift-clicks/hoppers/dropped-then-picked-up
 * deliveries. Issued documents carry no marker and tick through untouched.
 */
public class ForgeableDocumentItem extends Item {
    public ForgeableDocumentItem(Properties properties) {
        super(properties);
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        ForgeCrafting.resolveIfPending(stack, player);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity,
                            int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        ForgeCrafting.resolveIfPending(stack, entity);
    }
}
