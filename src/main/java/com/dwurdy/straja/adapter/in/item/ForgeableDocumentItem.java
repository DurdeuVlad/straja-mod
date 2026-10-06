package com.dwurdy.straja.adapter.in.item;

import com.dwurdy.straja.adapter.in.crafting.ForgeCrafting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * #247 — document-class items that double as smithing-table forge results.
 * {@code onCraftedBy} only fires when the item is produced by a crafting
 * station; issued documents arrive via {@code give()} and never trigger it.
 * The one crafting path that yields these items is the black-market
 * exemplar+stock+carbon recipe, so every take resolves a forge attempt.
 */
public class ForgeableDocumentItem extends Item {
    public ForgeableDocumentItem(Properties properties) {
        super(properties);
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        ForgeCrafting.onSmithingTake(stack, player);
    }
}
