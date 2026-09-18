package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.bootstrap.StrajaRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Physical whistle surface; all authorization stays in IncidentService. */
public final class AlarmWhistleItem extends Item {
    public AlarmWhistleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            StrajaRuntime runtime = StrajaRuntime.get();
            if (runtime != null) {
                runtime.expansionRoleplay().useWhistle(
                        new MinecraftPlayerGateway(serverPlayer.getServer(), serverPlayer.getUUID()));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
