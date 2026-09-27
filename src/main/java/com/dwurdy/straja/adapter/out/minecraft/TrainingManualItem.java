package com.dwurdy.straja.adapter.out.minecraft;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WrittenBookItem;
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
        if (player instanceof ServerPlayer serverPlayer) {
            // Vanilla openItemGui gates the packet on stack.is(Items.WRITTEN_BOOK),
            // which a custom subclass never matches — the book would return SUCCESS
            // yet never open on a real client. Send the vanilla open-book packet
            // directly; the client's BookAccess.fromItem is component-gated, so the
            // WRITTEN_BOOK_CONTENT component set above is sufficient.
            WrittenBookItem.resolveBookComponents(
                    stack, serverPlayer.createCommandSourceStack(), serverPlayer);
            serverPlayer.containerMenu.broadcastChanges();
            serverPlayer.connection.send(new ClientboundOpenBookPacket(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
