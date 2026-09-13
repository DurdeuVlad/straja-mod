package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.List;

/**
 * Service equipment lease, reclaim and kit issue. Equipment is physical:
 * every piece carries a StrajaService marker; anything missing at reclaim is
 * charged against unpaid salary first and becomes equipmentDebt after that.
 */
public class EquipmentService {
    public static final String MARKER = "StrajaService";
    public static final String MARKER_ITEM = "StrajaServiceItem";
    public static final String MARKER_RANK = "StrajaServiceRank";

    private final StrajaContext ctx;

    public EquipmentService(StrajaContext ctx) {
        this.ctx = ctx;
    }

    public List<StrajaPolicies.EquipmentEntry> specsForRank(int rank) {
        return ctx.policies().serviceEquipment.getOrDefault(rank, List.of());
    }

    public ItemSpec createServiceStack(StrajaPolicies.EquipmentEntry spec, int rank) {
        return ItemSpec.of(spec.itemId(), spec.count())
                .named("[Straja] " + spec.label())
                .withData(MARKER, "1")
                .withData(MARKER_ITEM, spec.key())
                .withData(MARKER_RANK, String.valueOf(rank));
    }

    public boolean isServiceStack(ItemView stack, String key) {
        return "1".equals(stack.data(MARKER)) && key.equals(stack.data(MARKER_ITEM));
    }

    /** Issues rank equipment with a pre-persisted lease record (crash-safe). */
    public boolean issueServiceEquipment(PlayerGateway player, GuardState state) {
        if (state.serviceEquipment != null && !state.serviceEquipment.items.isEmpty()) {
            player.tell("Echipamentul de serviciu este deja alocat pentru tura curentă.");
            return false;
        }
        List<StrajaPolicies.EquipmentEntry> specs = specsForRank(state.rank);
        if (specs.isEmpty()) return true;

        GuardState.ServiceEquipment record = new GuardState.ServiceEquipment();
        record.rank = state.rank;
        record.issuedAt = ctx.clock().nowMillis();
        for (StrajaPolicies.EquipmentEntry spec : specs) {
            GuardState.ServiceEquipment.Item item = new GuardState.ServiceEquipment.Item(
                    spec.key(), spec.itemId(), Math.max(1, spec.count()),
                    Math.max(0, spec.replacementCost()), spec.label());
            item.serial = ctx.ids().token();
            item.delivered = false;
            record.items.add(item);
        }
        state.serviceEquipment = record;
        ctx.players().write(player.uuid(), state); // persist lease before any give()

        int delivered = 0;
        for (int index = 0; index < specs.size(); index++) {
            ItemSpec stack = createServiceStack(specs.get(index), state.rank)
                    .withData("StrajaServiceSerial", record.items.get(index).serial);
            if (player.giveVerified(stack)) {
                record.items.get(index).delivered = true;
                // Persist each delivery so a crash mid-issue keeps the lease accurate.
                ctx.players().write(player.uuid(), state);
                delivered++;
            }
        }
        ctx.players().write(player.uuid(), state);
        if (delivered == specs.size()) {
            player.tell("Echipament de serviciu primit: " + labels(specs) + ". Se returnează la finalul turei.");
            return true;
        }
        player.tell("Echipamentul de serviciu a fost acordat parțial. Anunță Comisaru' înainte să începi patrula.");
        return false;
    }

    /** Pays equipment debt out of unpaid salary. Returns the amount settled. */
    public int settleEquipmentDebt(GuardState state) {
        int debt = Math.max(0, state.equipmentDebt);
        int salary = Math.max(0, state.unpaidSalary);
        if (debt == 0 || salary == 0) return 0;
        int paid = Math.min(debt, salary);
        state.equipmentDebt = debt - paid;
        state.unpaidSalary = salary - paid;
        return paid;
    }

    /** Reclaims issued equipment; missing pieces are charged then debted. */
    public ReclaimResult reclaimServiceEquipment(PlayerGateway player, GuardState state) {
        GuardState.ServiceEquipment record = state.serviceEquipment;
        if (record == null || record.items.isEmpty()) {
            state.serviceEquipment = null;
            return new ReclaimResult(List.of(), List.of(), 0, state.equipmentDebt);
        }
        settleEquipmentDebt(state);
        List<String> returned = new ArrayList<>();
        List<Missing> missing = new ArrayList<>();
        for (GuardState.ServiceEquipment.Item item : record.items) {
            if (!item.delivered) continue; // never handed over — cannot be missing
            int needed = Math.max(1, item.count);
            int taken = takeServiceEquipment(player, item, needed);
            if (taken > 0) returned.add(item.label + " ×" + taken);
            if (taken < needed) missing.add(new Missing(item.label, needed - taken, item.replacementCost));
        }
        int missingCost = missing.stream().mapToInt(m -> m.count * m.cost).sum();
        int available = Math.max(0, state.unpaidSalary);
        int charged = Math.min(available, missingCost);
        state.unpaidSalary = available - charged;
        state.equipmentDebt = Math.max(0, state.equipmentDebt) + (missingCost - charged);
        state.serviceEquipment = null;

        if (!missing.isEmpty()) {
            player.tell("Echipament lipsă: " + missingText(missing) + ". Cost: " + missingCost
                    + " monede; dedus acum: " + charged + "; datorie echipament: " + state.equipmentDebt + ".");
        } else {
            player.tell("Echipamentul de serviciu a fost returnat integral.");
        }
        return new ReclaimResult(returned, missing, charged, state.equipmentDebt);
    }

    private int takeServiceEquipment(PlayerGateway player, GuardState.ServiceEquipment.Item item, int needed) {
        var inventory = player.inventory();
        int remaining = needed;
        for (int slot = 0; slot < inventory.slots() && remaining > 0; slot++) {
            ItemView stack = inventory.stackAt(slot);
            if (stack.isEmpty() || !item.id.equals(stack.id())) continue;
            if (!isServiceStack(stack, item.key)) continue;
            if (item.serial != null && !item.serial.equals(stack.data("StrajaServiceSerial"))) continue;
            remaining -= inventory.extract(slot, Math.min(remaining, stack.count())).count();
        }
        return needed - remaining;
    }

    /** Permanent kit for the rank; items already leased as equipment are skipped. */
    public boolean giveKit(PlayerGateway player, GuardState state) {
        List<ItemSpec> kit = ctx.policies().kits.get(state.rank);
        if (kit == null) return false;
        var serviceIds = specsForRank(state.rank).stream()
                .map(StrajaPolicies.EquipmentEntry::itemId).toList();
        List<ItemSpec> stacks = kit.stream()
                .filter(spec -> !serviceIds.contains(spec.id()))
                .toList();
        if (!player.inventory().canReceive(stacks)) {
            player.tell("Kitul nu încape în inventar. Eliberează sloturi și încearcă din nou.");
            return false;
        }
        for (ItemSpec stack : stacks) {
            if (!player.giveVerified(stack)) {
                player.tell("Kitul nu a putut fi predat complet.");
                return false;
            }
        }
        state.kitClaimedRank = state.rank;
        state.regearPending = false;
        ctx.players().write(player.uuid(), state);
        player.tell("Echipamentul pentru " + ctx.policies().rankName(state.rank)
                + " a fost acordat.");
        return true;
    }

    private static String labels(List<StrajaPolicies.EquipmentEntry> specs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(specs.get(i).label());
        }
        return sb.toString();
    }

    private static String missingText(List<Missing> missing) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(missing.get(i).label()).append(" ×").append(missing.get(i).count());
        }
        return sb.toString();
    }

    public record Missing(String label, int count, int cost) {}
    public record ReclaimResult(List<String> returned, List<Missing> missing, int charged, int debt) {}
}
