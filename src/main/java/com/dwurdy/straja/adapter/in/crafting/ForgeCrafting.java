package com.dwurdy.straja.adapter.in.crafting;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.application.service.ForgeryService;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * #247 — the physical take boundary for smithing-table forging. Runs inside
 * {@link net.minecraft.world.item.Item#onCraftedBy}, which fires on the take
 * (slots still full) — the only moment a roll is legal: UI previews never
 * see a result, so a forger can't reroll by reopening the table.
 *
 * <p>The exemplar (template slot 0) is returned to the forger's inventory:
 * a genuine reference inspires unlimited copies — only carbon paper is
 * consumed. The result stack is marked in place with the rolled
 * marking + claimed serial + hidden tier key; the registry shadow record is
 * written by {@link ForgeryService#forge}.</p>
 */
public final class ForgeCrafting {

    /** Resolves the forge attempt for a smithing-table take. Server-side only. */
    public static void onSmithingTake(ItemStack result, Player player) {
        if (player == null || player.level().isClientSide) return;
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !runtime.forgery().enabled()) return;
        if (!(player.containerMenu instanceof SmithingMenu)) return;

        ItemStack exemplar = player.containerMenu.getSlot(0).getItem();
        String resultId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(result.getItem()).toString();

        var actor = new MinecraftPlayerGateway(player.getServer(), player.getUUID());

        // Licensed inspectors never roll — a genuine copy through official
        // hands mirrors the exemplar's own data instead of forging.
        if (runtime.forgery().strikeIsAuthentic(actor)) {
            mirrorExemplar(exemplar, result);
        } else {
            ForgeryService.ForgeOutcome outcome =
                    runtime.forgery().forge(actor, resultId);
            if (outcome != null) stamp(result, outcome);
        }

        // The exemplar survives: return a copy before the menu shrinks slot 0.
        // A full inventory must not eat the original — drop it at the forger's feet.
        if (!exemplar.isEmpty()) {
            ItemStack kept = exemplar.copyWithCount(1);
            if (!player.getInventory().add(kept)) {
                player.drop(kept, false);
            }
        }
    }

    /** Authentic take: the copy carries the exemplar's own issuance data. */
    private static void mirrorExemplar(ItemStack exemplar, ItemStack result) {
        CustomData data = exemplar.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        CompoundTag copy = data.copyTag().copy();
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(copy));
    }

    private static void stamp(ItemStack stack, ForgeryService.ForgeOutcome outcome) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(ForgeryService.CLAIMED_SERIAL_KEY, outcome.claimedSerial());
            tag.putString(ForgeryService.MARK_KEY, outcome.marking());
            tag.putString(ForgeryService.TIER_KEY, outcome.tier().name());
        });
    }

    private ForgeCrafting() {}
}
