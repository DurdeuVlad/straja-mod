package com.dwurdy.straja.adapter.out.minecraft;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;

/** A genuine Minecraft written book whose pages come from the bundled manual resource. */
public final class TrainingManualItem extends WrittenBookItem {
    public TrainingManualItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT, GuardManualContent.content());
        }
        return super.use(level, player, hand);
    }
}
