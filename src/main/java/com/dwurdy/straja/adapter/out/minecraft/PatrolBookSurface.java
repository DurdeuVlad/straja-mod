package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.service.PatrolGuideService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * #248 — the physical patrol book. Builds a written_book whose
 * {@link PatrolGuideService#PATROL_DAY_KEY} stamp binds the edition to its
 * world-day: stale copies stay readable but are visibly dated, and the
 * issuing check refuses to hand out a second copy of today's edition.
 */
public final class PatrolBookSurface {

    private PatrolBookSurface() {}

    /** The world-day stamped on any patrol book the carrier holds, or -1. */
    /**
     * The most recent edition day stamped on any patrol guide the carrier
     * holds — every top-level slot plus books nested inside
     * bundles/containers is walked, and the LARGEST day wins so a stale
     * copy parked ahead of today's can't launder a same-day reissue.
     * Returns -1 when no stamped guide is found.
     */
    public static long carriedEditionDay(ServerPlayer carrier) {
        if (carrier == null) return -1;
        var inventory = carrier.getInventory();
        long best = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            best = Math.max(best, editionDayOn(inventory.getItem(i), 0));
        }
        return best;
    }

    private static long editionDayOn(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty() || depth > 2) return -1;
        long best = -1;
        if (stack.is(Items.WRITTEN_BOOK)) {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data != null) {
                String stamp = data.copyTag().getString(PatrolGuideService.PATROL_DAY_KEY);
                if (stamp != null && !stamp.isBlank()) {
                    try {
                        best = Long.parseLong(
                                stamp.substring(stamp.startsWith("ziua-") ? 5 : 0));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        var container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            for (ItemStack inner : container.nonEmptyItems()) {
                best = Math.max(best, editionDayOn(inner, depth + 1));
            }
        }
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack inner : bundle.items()) {
                best = Math.max(best, editionDayOn(inner, depth + 1));
            }
        }
        return best;
    }

    /** Builds this day's edition for the carrier; refuses same-day reissues. */
    public static boolean issue(ServerPlayer carrier, PatrolGuideService guide,
                                long worldDay) {
        if (carrier == null || guide == null) return false;
        if (carriedEditionDay(carrier) == worldDay) return false;
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : guide.pages(worldDay)) {
            pages.add(Filterable.passThrough(Component.literal(page == null ? "" : page)));
        }
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("Ghid de patrulare — ziua " + Math.max(0, worldDay)),
                "Comisariatul Straja", 0, pages, true));
        CompoundTag tag = new CompoundTag();
        tag.putString(PatrolGuideService.PATROL_DAY_KEY, guide.edition(worldDay));
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (!carrier.getInventory().add(book)) {
            carrier.drop(book, false);
        }
        return true;
    }
}
