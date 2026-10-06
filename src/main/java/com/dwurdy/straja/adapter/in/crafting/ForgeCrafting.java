package com.dwurdy.straja.adapter.in.crafting;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.application.service.ForgeryService;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * #247 — resolves {@code StrajaForgePending} markers into real forge results.
 * Called from two places on the item class: {@code onCraftedBy} (normal
 * smithing take — the only vanilla call site) and {@code inventoryTick}
 * (covers shift-click, hoppers, and any later delivery path the menu can't
 * reach). The marker, not the call site, is the discriminant — an item that
 * never passed through a forge recipe carries no marker and is ignored.
 *
 * <p>The roll happens only here, at delivery; previews never see it. The
 * serialized exemplar snapshot in the marker is refunded to the holder on
 * every path — one genuine reference inspires unlimited copies.</p>
 */
public final class ForgeCrafting {

    /**
     * If the stack carries a pending forge marker, roll/mirror it into a
     * finished product and refund the exemplar. Safe to call on any stack,
     * any tick — non-marked stacks return immediately.
     */
    public static void resolveIfPending(ItemStack stack, Entity holder) {
        if (stack == null || stack.isEmpty() || holder == null) return;
        if (holder.level().isClientSide) return;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.copyTag().contains(SmithingForgeRecipe.PENDING_KEY)) return;
        if (!(holder instanceof ServerPlayer sp)) return;
        ItemStack exemplar = readExemplar(data, sp);
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !runtime.forgery().enabled()) {
            // Engine off mid-take: resolve to a clean terminal state — the
            // marker must not linger as a tainted blank item (materials are
            // already spent by the slot-shrink), and the exemplar is still
            // sacred. The result is a plain, unforged copy.
            clearMarker(stack);
            refundExemplar(sp, exemplar);
            return;
        }

        var actor = new MinecraftPlayerGateway(sp.getServer(), sp.getUUID());
        String resultId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();

        if (runtime.forgery().strikeIsAuthentic(actor)) {
            // Licensed take: the copy carries the exemplar's own issuance data.
            CustomData exData = exemplar.isEmpty() ? null
                    : exemplar.get(DataComponents.CUSTOM_DATA);
            if (exData != null) {
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(exData.copyTag().copy()));
            }
        } else {
            ForgeryService.ForgeOutcome outcome =
                    runtime.forgery().forge(actor, resultId);
            if (outcome != null) stamp(stack, outcome);
        }
        clearMarker(stack);
        refundExemplar(sp, exemplar);
    }

    private static ItemStack readExemplar(CustomData data, ServerPlayer player) {
        Tag saved = data.copyTag().get(SmithingForgeRecipe.EXEMPLAR_KEY);
        if (!(saved instanceof CompoundTag tag) || tag.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return ItemStack.parse(player.registryAccess(), tag).orElse(ItemStack.EMPTY);
    }

    private static void clearMarker(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove(SmithingForgeRecipe.PENDING_KEY);
            tag.remove(SmithingForgeRecipe.EXEMPLAR_KEY);
        });
    }

    private static void refundExemplar(ServerPlayer player, ItemStack exemplar) {
        if (exemplar.isEmpty()) return;
        ItemStack kept = exemplar.copyWithCount(1);
        if (!player.getInventory().add(kept)) {
            player.drop(kept, false);
        }
    }

    private static void stamp(ItemStack stack, ForgeryService.ForgeOutcome outcome) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(ForgeryService.CLAIMED_SERIAL_KEY, outcome.claimedSerial());
            tag.putString(ForgeryService.MARK_KEY, outcome.marking());
            tag.putString(ForgeryService.TIER_KEY, outcome.tier().name());
        });
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> lines = new ArrayList<>(lore.lines());
        lines.add(Component.literal("Marca: " + outcome.marking()));
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    private ForgeCrafting() {}
}
