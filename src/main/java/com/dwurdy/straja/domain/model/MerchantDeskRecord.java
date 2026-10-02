package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A quartermaster/merchant desk (AT5): an NPC-attended buy counter whose
 * sold goods route physically into an ordered chest list. Prices are base
 * currency units (bronze = 1) resolved through the configured coin tiers.
 */
public class MerchantDeskRecord {
    public String id = "";
    /** CustomNPCs entity UUID backing the desk. */
    public String npcUuid = "";
    public String npcName = "";
    public String dimension = "minecraft:overworld";
    public StoragePoint deskPos;
    /** Ordered chests — goods fill sequentially with overflow into the next. */
    public List<StoragePoint> chests = new ArrayList<>();
    /** item id -> unit price in base currency units. */
    public Map<String, Integer> sellTable = new LinkedHashMap<>();
    /** When true, payouts credit the seller's labor account instead of coins (camps). */
    public boolean creditsLaborAccount;

    public Integer priceOf(String itemId) {
        return sellTable.get(itemId);
    }

    /** Self-heals explicit {@code null}s a Gson payload may carry. */
    public void normalize() {
        if (id == null) id = "";
        if (npcUuid == null) npcUuid = "";
        if (npcName == null) npcName = "";
        if (dimension == null) dimension = "minecraft:overworld";
        if (chests == null) chests = new ArrayList<>();
        chests.removeIf(java.util.Objects::isNull);
        if (sellTable == null) sellTable = new LinkedHashMap<>();
        sellTable.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
    }
}
