package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.MerchantDeskRecord;
import com.dwurdy.straja.domain.model.MerchantDeskStore;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.domain.model.TradeLedgerEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LAW-005 / AT5 merchant desks: an NPC-attended buy counter whose sold goods
 * route physically into an ordered chest list — chest 1 fills first, overflow
 * spills into chest 2, and so on. Payment is the 4-tier coin economy through
 * {@code CurrencyProvider} for civilians, or a {@code laborAccount} credit for
 * camp prisoners when the desk is flagged {@code creditsLaborAccount}.
 *
 * <p>Atomicity: chest capacity is simulated before anything moves, then the
 * inventory is extracted, then coins are paid (or the account credited), and
 * only then are stacks inserted into the chests. A capacity race that leaves
 * a leftover returns the unsold remainder to the seller and the ledger entry
 * records the placed amounts — items are never voided.
 */
public final class MerchantDeskService {
    private static final int LEDGER_KEEP = 500;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    /** Armed chest picks: admin uuid -> desk id waiting for a click. */
    private final Map<UUID, String> picks = new HashMap<>();

    public MerchantDeskService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private MerchantDeskStore store() {
        return ctx.merchantDesks().read();
    }

    // ------------------------------------------------------------ admin

    public boolean createDesk(PlayerGateway actor, String deskId, String npcRef) {
        if (!authorized(actor)) return refuseAuthority(actor, "desk_create");
        if (deskId == null || deskId.isBlank() || !deskId.matches("[a-zA-Z0-9_\\-]{1,32}")) {
            actor.refuse("straja.desk.bad_id", "straja.remedy.fix_retry");
            return false;
        }
        var store = store();
        if (store.desk(deskId) != null) {
            actor.refuse("straja.desk.dup", "straja.remedy.fix_retry", deskId);
            return false;
        }
        var desk = new MerchantDeskRecord();
        desk.id = deskId;
        if (actor.uuid() != null) {
            desk.dimension = actor.dimension();
            desk.deskPos = new StoragePoint(actor.dimension(),
                    (int) Math.floor(actor.x()), (int) Math.floor(actor.y()),
                    (int) Math.floor(actor.z()));
        }
        if (npcRef != null && !npcRef.isBlank()) {
            try {
                UUID.fromString(npcRef);
                desk.npcUuid = npcRef;
            } catch (IllegalArgumentException ex) {
                desk.npcName = npcRef;
            }
        }
        store.put(desk);
        ctx.merchantDesks().write(store);
        actor.tellKey("straja.desk.created", deskId);
        audit.record("desk_create", actor.name(), uuidOf(actor), deskId, "",
                "SUCCESS", "npc=" + npcRef);
        return true;
    }

    public boolean removeDesk(PlayerGateway actor, String deskId) {
        if (!authorized(actor)) return refuseAuthority(actor, "desk_remove");
        var store = store();
        if (store.desk(deskId) == null) {
            actor.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return false;
        }
        store.remove(deskId);
        ctx.merchantDesks().write(store);
        actor.tellKey("straja.desk.removed", deskId);
        audit.record("desk_remove", actor.name(), uuidOf(actor), deskId, "", "SUCCESS", "");
        return true;
    }

    public boolean setPrice(PlayerGateway actor, String deskId, String itemId, int price) {
        if (!authorized(actor)) return refuseAuthority(actor, "desk_price");
        var store = store();
        var desk = store.desk(deskId);
        if (desk == null) {
            actor.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return false;
        }
        if (itemId == null || itemId.isBlank() || !itemId.contains(":")) {
            actor.refuse("straja.desk.bad_item", "straja.remedy.fix_retry");
            return false;
        }
        if (price <= 0) {
            desk.sellTable.remove(itemId);
            actor.tellKey("straja.desk.price_removed", itemId, deskId);
        } else {
            desk.sellTable.put(itemId, price);
            actor.tellKey("straja.desk.price_set", itemId, deskId, price);
        }
        store.put(desk);
        ctx.merchantDesks().write(store);
        audit.record("desk_price", actor.name(), uuidOf(actor), deskId, "",
                "SUCCESS", itemId + "=" + price);
        return true;
    }

    public boolean setCreditsLabor(PlayerGateway actor, String deskId, boolean on) {
        if (!authorized(actor)) return refuseAuthority(actor, "desk_labor");
        var store = store();
        var desk = store.desk(deskId);
        if (desk == null) {
            actor.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return false;
        }
        desk.creditsLaborAccount = on;
        store.put(desk);
        ctx.merchantDesks().write(store);
        actor.tellKey(on ? "straja.desk.labor_on" : "straja.desk.labor_off", deskId);
        return true;
    }

    /** Arms the admin's next chest click as a desk-chest pick. */
    public boolean setPickMode(PlayerGateway admin, String deskId) {
        if (!authorized(admin)) return refuseAuthority(admin, "desk_pick");
        if ("off".equals(deskId) || deskId == null || deskId.isBlank()) {
            picks.remove(admin.uuid());
            admin.tellKey("straja.desk.pick_off");
            return true;
        }
        if (store().desk(deskId) == null) {
            admin.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return false;
        }
        picks.put(admin.uuid(), deskId);
        admin.tellKey("straja.desk.pick_arm", deskId);
        return true;
    }

    /** Consumes an armed desk-chest pick; true when the click was handled. */
    public boolean onPickClick(PlayerGateway admin, String dimension, int x, int y, int z) {
        String deskId = picks.get(admin.uuid());
        if (deskId == null) return false;
        if (!ctx.containers().isContainer(dimension, x, y, z)) {
            admin.refuse("straja.storage.pick_not_container", "straja.remedy.fix_retry");
            return true;
        }
        var store = store();
        var desk = store.desk(deskId);
        if (desk == null) {
            picks.remove(admin.uuid());
            admin.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return true;
        }
        String canon = ctx.containers().canonicalKey(dimension, x, y, z);
        boolean dup = desk.chests.stream().anyMatch(p -> p != null
                && ctx.containers().canonicalKey(p.dimension(), p.x(), p.y(), p.z())
                        .equals(canon));
        if (dup) {
            admin.tellKey("straja.storage.pick_chest_dup", canon);
            return true;
        }
        desk.chests.add(new StoragePoint(dimension, x, y, z));
        store.put(desk);
        ctx.merchantDesks().write(store);
        admin.tellKey("straja.desk.chest_added", canon, deskId, desk.chests.size());
        return true;
    }

    public void disarmPick(UUID playerUuid) {
        picks.remove(playerUuid);
    }

    /**
     * AT5 quartermaster profile: derives the desk's sell table from the camp's
     * exit-gate carry bans (plus site-local illegal items), flags the desk for
     * penal labor credit, and stamps it onto the camp record. Idempotent —
     * re-running refreshes missing rows at the given default price.
     */
    public boolean setupQuartermaster(PlayerGateway actor, String campId, String deskId,
                                      int unitPrice) {
        if (!authorized(actor)) return refuseAuthority(actor, "desk_quartermaster");
        var campStore = ctx.laborCamps().read();
        var camp = campStore.camp(campId);
        if (camp == null) {
            actor.refuse("straja.desk.no_camp", "straja.remedy.fix_retry", campId);
            return false;
        }
        if (camp.exitCheckpointId == null || camp.exitCheckpointId.isBlank()) {
            actor.refuse("straja.desk.no_exit", "straja.remedy.fix_retry", campId);
            return false;
        }
        var store = store();
        var desk = store.desk(deskId);
        if (desk == null) {
            actor.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return false;
        }
        if (unitPrice <= 0) {
            actor.refuse("straja.desk.bad_price", "straja.remedy.fix_retry");
            return false;
        }
        var sites = ctx.lawCheckpoints().read();
        var site = sites.checkpoint(camp.exitCheckpointId);
        if (site == null) {
            actor.refuse("straja.desk.no_site", "straja.remedy.fix_retry", camp.exitCheckpointId);
            return false;
        }
        site.normalize();
        var items = new java.util.LinkedHashSet<String>();
        items.addAll(site.localIllegalItems);
        for (var banned : site.roleCarryBans.values()) {
            if (banned != null) items.addAll(banned);
        }
        if (items.isEmpty()) {
            actor.refuse("straja.desk.no_bans", "straja.remedy.fix_retry", camp.exitCheckpointId);
            return false;
        }
        for (String itemId : items) {
            if (itemId != null && !itemId.isBlank()) desk.sellTable.putIfAbsent(itemId, unitPrice);
        }
        desk.creditsLaborAccount = true;
        store.put(desk);
        ctx.merchantDesks().write(store);
        camp.quartermasterDeskId = desk.id;
        campStore.put(camp);
        ctx.laborCamps().write(campStore);
        actor.tellKey("straja.desk.quartermaster_ok", desk.id, campId, items.size());
        audit.record("desk_quartermaster", actor.name(), uuidOf(actor), deskId, "",
                "SUCCESS", "camp=" + campId + " items=" + items.size());
        return true;
    }

    public void listDesks(PlayerGateway viewer) {
        var store = store();
        if (store.desks().isEmpty()) {
            viewer.tellKey("straja.desk.none");
            return;
        }
        for (var desk : store.desks().values()) {
            viewer.tellKey("straja.desk.info", desk.id, desk.npcName.isEmpty() ? desk.npcUuid
                    : desk.npcName, desk.chests.size(), desk.sellTable.size());
        }
    }

    /** `/straja desk ledger <deskId> [seller]` — newest entries first. */
    public void showLedger(PlayerGateway viewer, String deskId, String sellerRef) {
        var store = store();
        var desk = store.desk(deskId);
        if (desk == null) {
            viewer.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return;
        }
        String filter = sellerRef == null ? "" : sellerRef.trim().toLowerCase();
        List<TradeLedgerEntry> matches = new ArrayList<>();
        for (var e : store.trades()) {
            if (e == null || !deskId.equals(e.deskId)) continue;
            if (!filter.isEmpty() && !e.sellerUuid.equalsIgnoreCase(filter)
                    && !e.sellerName.toLowerCase().contains(filter)) continue;
            matches.add(e);
        }
        if (matches.isEmpty()) {
            viewer.tellKey("straja.desk.ledger_empty", deskId);
            return;
        }
        int shown = 0;
        for (int i = matches.size() - 1; i >= 0 && shown < 10; i--, shown++) {
            var e = matches.get(i);
            viewer.tellKey("straja.desk.ledger_row", e.id, e.sellerName,
                    e.itemsSold.toString(), e.baseUnits,
                    e.creditedToLabor ? "LABOR" : "CASH");
        }
    }

    // ------------------------------------------------------------ trade

    /**
     * Sells the seller's carried goods against the desk's sell table.
     * {@code itemFilter}/{@code maxCount} narrow the sale; empty/0 means
     * everything offered.
     */
    public boolean sell(PlayerGateway seller, String deskId, String itemFilter, int maxCount) {
        var store = store();
        var desk = store.desk(deskId);
        if (desk == null) {
            seller.refuse("straja.desk.no_desk", "straja.remedy.fix_retry", deskId);
            return false;
        }
        if (desk.chests.isEmpty()) {
            seller.refuse("straja.desk.no_chests", "straja.remedy.alert_manager");
            return false;
        }
        if (desk.sellTable.isEmpty()) {
            seller.refuse("straja.desk.no_table", "straja.remedy.alert_manager");
            return false;
        }
        // The trade surface is the desk itself — sellers must stand at it.
        if (desk.deskPos != null && seller.uuid() != null) {
            double dx = seller.x() - desk.deskPos.x();
            double dy = seller.y() - desk.deskPos.y();
            double dz = seller.z() - desk.deskPos.z();
            if (!desk.dimension.equals(seller.dimension())
                    || dx * dx + dy * dy + dz * dz > 64.0) {
                seller.refuse("straja.desk.too_far", "straja.remedy.desk_table", desk.id);
                return false;
            }
        }

        InventoryView inv = seller.inventory();
        Map<String, Integer> sale = new LinkedHashMap<>();
        for (var entry : desk.sellTable.entrySet()) {
            String itemId = entry.getKey();
            if (itemFilter != null && !itemFilter.isBlank() && !itemId.equals(itemFilter)) continue;
            Integer price = entry.getValue();
            if (price == null || price <= 0) continue;
            int avail = inv.countOf(itemId);
            if (avail <= 0) continue;
            sale.put(itemId, maxCount > 0 ? Math.min(avail, maxCount) : avail);
        }
        if (sale.isEmpty()) {
            seller.refuse("straja.desk.nothing_to_sell", "straja.remedy.desk_table", desk.id);
            return false;
        }

        long total = 0;
        for (var e : sale.entrySet()) {
            total += (long) desk.priceOf(e.getKey()) * e.getValue();
            if (total > Integer.MAX_VALUE) {
                seller.refuse("straja.desk.too_much", "straja.remedy.retry");
                return false;
            }
        }

        // Atomic preflight: simulate the sequential fill before anything moves.
        if (!fitsAll(desk, sale)) {
            seller.refuse("straja.desk.chests_full", "straja.remedy.alert_manager");
            return false;
        }

        // Extract first: the stacks are held in memory until the chests commit
        // them, and freed slots leave room for the coin payout.
        record Held(String itemId, int count, String snbt) {}
        List<Held> held = new ArrayList<>();
        for (var e : sale.entrySet()) {
            int remaining = e.getValue();
            for (int slot = 0; slot < inv.slots() && remaining > 0; slot++) {
                ItemView v = inv.stackAt(slot);
                if (v.isEmpty() || !e.getKey().equals(v.id())) continue;
                String snbt = inv.snbtAt(slot);
                int take = Math.min(remaining, v.count());
                ItemView taken = inv.extract(slot, take);
                if (taken.isEmpty()) continue;
                held.add(new Held(e.getKey(), taken.count(), snbt));
                remaining -= taken.count();
            }
        }

        // Physical routing: chest 1 fills first, overflow walks the list.
        // Any race that leaves a leftover hands the unsold rest straight back.
        Map<String, Integer> placed = new LinkedHashMap<>();
        int returned = 0;
        for (Held h : held) {
            int leftover = insertAll(desk, h.itemId(), h.count(), h.snbt());
            int in = h.count() - Math.max(0, leftover);
            if (in > 0) placed.merge(h.itemId(), in, Integer::sum);
            if (leftover > 0) {
                seller.giveStack(h.itemId(), leftover, h.snbt());
                returned += leftover;
            }
        }
        if (placed.isEmpty()) {
            // Everything bounced — nothing was committed, nothing is owed.
            seller.refuse("straja.desk.chests_full", "straja.remedy.alert_manager");
            return false;
        }

        // Payment only after goods are physically committed — and only for the
        // amounts that actually landed.
        boolean laborCredit = desk.creditsLaborAccount && isCampPrisoner(seller);
        long paidTotal = 0;
        for (var e : placed.entrySet()) paidTotal += (long) desk.priceOf(e.getKey()) * e.getValue();
        int baseUnits = (int) Math.min(paidTotal, Integer.MAX_VALUE);
        if (laborCredit) {
            var reg = ctx.prisonerRegister().read();
            var rec = reg.prisoner(seller.uuid().toString());
            if (rec != null) {
                rec.laborAccount += baseUnits;
                ctx.prisonerRegister().write(reg);
            } else {
                laborCredit = false; // register row vanished mid-sale — pay cash
            }
        }
        if (!laborCredit) {
            var deposit = ctx.currency().deposit(seller, baseUnits,
                    "desk-" + desk.id + "-" + ctx.ids().token());
            if (!deposit.ok()) {
                // Compensation: pull the committed goods back out of the desk
                // chests and return everything the sale still owes the seller.
                for (var e : placed.entrySet()) {
                    int owed = e.getValue();
                    for (StoragePoint chest : desk.chests) {
                        if (owed <= 0 || chest == null) continue;
                        owed -= ctx.containers().remove(chest.dimension(), chest.x(),
                                chest.y(), chest.z(), e.getKey(), owed);
                    }
                    seller.giveStack(e.getKey(), e.getValue(), null);
                }
                seller.refuse("straja.desk.payment_failed", "straja.remedy.retry");
                audit.record("desk_sale", seller.name(), uuidOf(seller), desk.id, "",
                        "REFUSED", "payment_failed_after_commit; goods reclaimed");
                return false;
            }
        }

        var entry = new TradeLedgerEntry("TR-" + ctx.ids().newId("T"), ctx.clock().nowMillis(),
                desk.id, seller.uuid() == null ? "" : seller.uuid().toString());
        entry.sellerName = seller.name();
        entry.itemsSold.putAll(placed);
        entry.baseUnits = baseUnits;
        entry.creditedToLabor = laborCredit;
        store.appendTrade(entry, LEDGER_KEEP);
        ctx.merchantDesks().write(store);
        audit.record("desk_sale", seller.name(), uuidOf(seller), desk.id, "",
                "SUCCESS", "items=" + placed + " base=" + baseUnits
                        + (laborCredit ? " labor" : " cash")
                        + (returned > 0 ? " returned=" + returned : ""));
        seller.tellKey(laborCredit ? "straja.desk.sold_labor" : "straja.desk.sold",
                desk.id, baseUnits);
        return true;
    }

    /** Sequential insert across the desk's chests; returns the leftover count. */
    private int insertAll(MerchantDeskRecord desk, String itemId, int count, String snbt) {
        int remaining = count;
        for (StoragePoint chest : desk.chests) {
            if (remaining <= 0 || chest == null) continue;
            int left = ctx.containers().insertStack(chest.dimension(), chest.x(), chest.y(),
                    chest.z(), itemId, remaining, snbt);
            if (left >= 0) remaining = left;
        }
        return remaining;
    }

    /** Simulates the sequential fill — merge room first, shared empties after. */
    private boolean fitsAll(MerchantDeskRecord desk, Map<String, Integer> sale) {
        Map<String, Integer> remaining = new LinkedHashMap<>(sale);
        for (StoragePoint chest : desk.chests) {
            if (chest == null) continue;
            var cap = ctx.containers().capacity(chest.dimension(), chest.x(), chest.y(), chest.z());
            if (cap == null) continue;
            int empty = cap.emptySlots();
            for (var e : remaining.entrySet()) {
                if (e.getValue() <= 0) continue;
                int limit = Math.max(1, ctx.containers().stackLimit(e.getKey()));
                int merged = Math.min(e.getValue(),
                        cap.mergeRoom().getOrDefault(e.getKey(), 0));
                int afterMerge = e.getValue() - merged;
                int slotsFit = Math.min(empty, (afterMerge + limit - 1) / limit);
                int placed = merged + slotsFit * limit;
                empty -= (placed - merged + limit - 1) / limit;
                e.setValue(e.getValue() - placed);
            }
        }
        return remaining.values().stream().allMatch(v -> v <= 0);
    }

    private boolean isCampPrisoner(PlayerGateway seller) {
        if (seller.uuid() == null) return false;
        var rec = ctx.prisonerRegister().read().prisoner(seller.uuid().toString());
        return rec != null && rec.status == PrisonerStatus.IN_CAMP
                && rec.assignedCampId != null && !rec.assignedCampId.isBlank();
    }

    private boolean authorized(PlayerGateway actor) {
        return players.isCommissioner(actor)
                || players.hasCapability(actor, Capability.EXECUTE_ARRESTS);
    }

    private boolean refuseAuthority(PlayerGateway actor, String op) {
        actor.refuse("straja.desk.rank", "straja.remedy.jailer");
        audit.record(op, actor.name(), uuidOf(actor), "", "", "REFUSED", "missing_authority");
        return false;
    }

    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }
}
