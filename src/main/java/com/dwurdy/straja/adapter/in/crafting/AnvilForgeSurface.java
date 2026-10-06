package com.dwurdy.straja.adapter.in.crafting;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.application.service.ArtifactRegistryService;
import com.dwurdy.straja.application.service.ForgeryService;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import java.util.List;

/**
 * #247 — anvil strike surface for regulated artifacts. {@code seal_stamp}
 * (right slot) + a regulated item (left slot) produces a stamped result:
 * licensed inspectors strike authentic marks (registry-backed, PENDING),
 * everyone else rolls the forgery pyramid.
 *
 * <p>The roll happens ONLY in {@link AnvilRepairEvent} — the take — never in
 * the update preview. Preview shows the unmodified item copy; the physical
 * mark materializes when the strike lands, matching "you see your work when
 * the hammer falls".</p>
 */
public final class AnvilForgeSurface {
    private static final String STAMP = "straja:seal_stamp";

    private static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String dataOf(ItemStack stack, String key) {
        CustomData data = stack == null ? null : stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getString(key);
    }

    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return;
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (left.isEmpty() || !STAMP.equals(idOf(right))) return;
        String leftId = idOf(left);
        if (!runtime.policies().artifactRegulatedItemIds.contains(leftId)) return;
        boolean marked = !dataOf(left, ArtifactRegistryService.SERIAL_KEY).isBlank();
        if (marked) {
            // Re-striking a marked item is a refusal, not a new serial.
            event.setCanceled(true);
            return;
        }
        var actor = new MinecraftPlayerGateway(
                event.getPlayer().getServer(), event.getPlayer().getUUID());
        var plan = runtime.forgery().planAnvilStrike(actor, leftId, false);
        if (plan == null) {
            event.setCanceled(true);
            return;
        }
        ItemStack output = left.copy();
        event.setOutput(output);
        event.setMaterialCost(1);
        event.setCost(plan.xpLevels());
    }

    public static void onAnvilRepair(AnvilRepairEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return;
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        ItemStack output = event.getOutput();
        if (output.isEmpty() || !STAMP.equals(idOf(right))) return;
        String leftId = idOf(left);
        if (!runtime.policies().artifactRegulatedItemIds.contains(leftId)) return;
        var actor = new MinecraftPlayerGateway(
                event.getEntity().getServer(), event.getEntity().getUUID());
        var plan = runtime.forgery().planAnvilStrike(actor, leftId, false);
        if (plan == null) return;

        if (plan.authentic()) {
            String serial = runtime.artifactRegistry()
                    .register(actor, actor, leftId);
            if (serial != null) stamp(output, "#" + serial, serial, null);
        } else {
            ForgeryService.ForgeOutcome outcome =
                    runtime.forgery().forge(actor, leftId);
            if (outcome != null) {
                stamp(output, outcome.marking(), outcome.claimedSerial(),
                        outcome.tier().name());
            }
        }
    }

    /** The physical mark: claim data on the item + a lore line the player
     * (and any inspector who handles it) can read. */
    private static void stamp(ItemStack stack, String marking,
                              String claimedSerial, String tier) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(ArtifactRegistryService.MARK_KEY, marking);
            if (claimedSerial != null) {
                tag.putString(ArtifactRegistryService.SERIAL_KEY, claimedSerial);
            }
            if (tier != null) {
                tag.putString(ForgeryService.TIER_KEY, tier);
            }
        });
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> lines = new java.util.ArrayList<>(lore.lines());
        lines.add(Component.literal("Marca: " + marking));
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    private AnvilForgeSurface() {}
}
