package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.out.minecraft.WhipItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Native Straja items. Registry names keep the legacy kubejs names (minus the
 * kubejs: namespace) so migrations and documentation stay unambiguous.
 */
public final class StrajaItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(StrajaMod.MOD_ID);

    public static final DeferredItem<Item> ORDER_BOOK = ITEMS.register("order_book",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    /** Legacy ID kept for compatibility with already-issued books. */
    public static final DeferredItem<Item> MISSION_CARNET = ITEMS.register("mission_carnet",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static final DeferredItem<Item> ARCHIVE_FOLDER = ITEMS.register("archive_folder",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> ARCHIVE_DOCUMENT = ITEMS.register("archive_document",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> CARBON_PAPER = ITEMS.register("carbon_paper",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> ARCHIVE_STAMP = ITEMS.register("archive_stamp",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OFFICIAL_ENVELOPE = ITEMS.register("official_envelope",
            () -> new Item(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<Item> ROOM_MARKER = ITEMS.register("room_marker",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<Item> PRISON_MARKER = ITEMS.register("prison_marker",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    // Physical admin tools (CustomNPCs-style, one item per verb). Creative
    // inventory / `/straja setup tools` only — no recipes, non-stackable.
    public static final DeferredItem<Item> NPC_WAND = ITEMS.register("npc_wand",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final DeferredItem<Item> PATROL_WAND = ITEMS.register("patrol_wand",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final DeferredItem<Item> SURVEY_ROD = ITEMS.register("survey_rod",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final DeferredItem<Item> NPC_CLONER = ITEMS.register("npc_cloner",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredItem<Item> CUFFS = ITEMS.register("cuffs",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<Item> CUFF_KEY = ITEMS.register("cuff_key",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> BOLT_CUTTERS = ITEMS.register("bolt_cutters",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    /** Legacy registry ID for the same tool; still accepted by release logic. */
    public static final DeferredItem<Item> CROWBAR = ITEMS.register("crowbar",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<Item> ROPE = ITEMS.register("rope",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> HEAD_SACK = ITEMS.register("head_sack",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> BATON = ITEMS.register("baton",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<WhipItem> WHIP = ITEMS.register("whip",
            () -> new WhipItem(WhipItem.properties()));
    public static final DeferredItem<Item> KEYCHAIN = ITEMS.register("keychain",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredItem<Item> FINE_BOOK = ITEMS.register("fine_book",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<Item> FINE_NOTICE = ITEMS.register("fine_notice",
            () -> new Item(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> TRAINING_MANUAL = ITEMS.register("training_manual",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    private StrajaItems() {}

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
