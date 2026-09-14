package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.CurrencyProvider;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.List;

/**
 * Armorer NPC shop (EQ-004/EQ-005). Two shelves:
 * <ul>
 *   <li><b>Stock</b> — rank-gated gear bought with physical coins through the
 *       atomic {@link CurrencyProvider}; a failed delivery refunds the
 *       withdrawal via a receipted deposit.</li>
 *   <li><b>Reserves</b> — replacement gear priced in spendable
 *       {@code requisitionPoints}; points are restored if delivery fails.
 *       Promotion {@code serviceBlocks} are never spent.</li>
 * </ul>
 * All checks are server-authoritative; purchases are audited.
 */
public class ArmoryService implements com.dwurdy.straja.application.port.in.ArmoryUseCase {

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public ArmoryService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    /** Eligibility gate for both shelves: an active guard in good standing. */
    private boolean eligible(GuardState state) {
        return state.rank >= Rank.STAGIAR.level()
                && !state.suspended && !state.fired && !state.resigned;
    }

    /** Stock and reserve offers the player's rank can see (menu display). */
    @Override
    public List<Offer> offers(PlayerGateway player) {
        GuardState state = players.state(player);
        List<Offer> out = new ArrayList<>();
        if (!eligible(state)) return out;
        for (StrajaPolicies.ArmoryItem item : ctx.policies().armoryStock) {
            if (state.rank >= item.minRank()) {
                out.add(new Offer(item.key(), item.itemId(), item.count(), item.cost(), item.minRank(), false));
            }
        }
        for (StrajaPolicies.ArmoryItem item : ctx.policies().armoryReserves) {
            if (state.rank >= item.minRank()) {
                out.add(new Offer(item.key(), item.itemId(), item.count(), item.cost(), item.minRank(), true));
            }
        }
        return out;
    }

    /** Coin purchase from the stock shelf. */
    @Override
    public boolean buy(PlayerGateway player, String key) {
        GuardState state = players.state(player);
        StrajaPolicies.ArmoryItem item = find(ctx.policies().armoryStock, key);
        if (!eligible(state)) {
            player.tell("Armurierul deservește doar străjeri activi.");
            return refused("armory_buy", player, key, "not_eligible");
        }
        if (item == null || state.rank < item.minRank()) {
            player.tell("Armurierul nu are acest articol pentru rangul tău.");
            return refused("armory_buy", player, key, "unavailable");
        }
        if (!ctx.currency().available()) {
            player.tell("Moneda externă nu este configurată; armurierul nu poate încasa.");
            return refused("armory_buy", player, key, "currency_unavailable");
        }
        CurrencyProvider.Withdrawal withdrawal = ctx.currency().withdraw(player, item.cost());
        if (!withdrawal.ok()) {
            player.tell("Fonduri insuficiente. Preț: " + item.cost() + " monede.");
            return refused("armory_buy", player, key, "insufficient_funds");
        }
        if (!player.giveVerified(ItemSpec.of(item.itemId(), item.count()))) {
            String refundId = "armory-refund:" + player.uuid() + ":" + ctx.clock().nowMillis() + ":" + key;
            var refund = ctx.currency().deposit(player, item.cost(), refundId);
            audit.record("armory_buy", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(),
                    refund.ok() ? "REFUNDED" : "FAILED",
                    key + ",delivery_failed,refund=" + (refund.ok() ? "ok" : "failed"));
            player.tell(refund.ok()
                    ? "Articolul nu a putut fi predat; monedele au fost returnate."
                    : "Articolul nu a putut fi predat și returnarea monedelor a eșuat — anunță Comisaru'.");
            return false;
        }
        audit.record("armory_buy", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS",
                key + ",price=" + item.cost());
        player.tell("Ai cumpărat " + item.count() + "× " + item.itemId()
                + " pentru " + item.cost() + " monede.");
        return true;
    }

    /** Requisition-point purchase from the reserve shelf. */
    @Override
    public boolean buyReserve(PlayerGateway player, String key) {
        GuardState state = players.state(player);
        StrajaPolicies.ArmoryItem item = find(ctx.policies().armoryReserves, key);
        if (!eligible(state)) {
            player.tell("Armurierul deservește doar străjeri activi.");
            return refused("armory_reserve", player, key, "not_eligible");
        }
        if (item == null || state.rank < item.minRank()) {
            player.tell("Armurierul nu are această rezervă pentru rangul tău.");
            return refused("armory_reserve", player, key, "unavailable");
        }
        if (state.requisitionPoints < item.cost()) {
            player.tell("Puncte de rechiziție insuficiente. Ai " + state.requisitionPoints
                    + ", cost: " + item.cost() + ".");
            return refused("armory_reserve", player, key, "insufficient_points");
        }
        if (!player.inventory().canReceive(List.of(ItemSpec.of(item.itemId(), item.count())))) {
            player.tell("Inventarul este plin; punctele nu au fost cheltuite.");
            return refused("armory_reserve", player, key, "inventory_full");
        }
        state.requisitionPoints -= item.cost();
        players.save(player.uuid(), state);
        if (!player.giveVerified(ItemSpec.of(item.itemId(), item.count()))) {
            state.requisitionPoints += item.cost();
            players.save(player.uuid(), state);
            audit.record("armory_reserve", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "FAILED",
                    key + ",delivery_failed,points_restored");
            player.tell("Articolul nu a putut fi predat; punctele au fost restituite.");
            return false;
        }
        audit.record("armory_reserve", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS",
                key + ",points=" + item.cost());
        player.tell("Ai ridicat " + item.count() + "× " + item.itemId()
                + " pentru " + item.cost() + " puncte de rechiziție. Sold: "
                + state.requisitionPoints + ".");
        return true;
    }

    private boolean refused(String action, PlayerGateway player, String key, String reason) {
        audit.record(action, player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "REFUSED", key + "," + reason);
        return false;
    }

    private static StrajaPolicies.ArmoryItem find(List<StrajaPolicies.ArmoryItem> items, String key) {
        for (StrajaPolicies.ArmoryItem item : items) {
            if (item.key().equals(key)) return item;
        }
        return null;
    }
}
