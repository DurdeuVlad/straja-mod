package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.List;

/**
 * Permanent rank kits. Gear is owned outright — granted at rank-up and never
 * reclaimed at shift boundaries. There is no lease, no service markers and no
 * equipment debt; resupply runs through the armory ({@link ArmoryService}).
 */
public class EquipmentService {

    private final StrajaContext ctx;

    public EquipmentService(StrajaContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Delivers the full permanent kit for the player's current rank. Marks
     * {@code kitClaimedRank} only on complete delivery; a partial failure
     * leaves the kit claimable so it can be retried via {@code /straja kit}
     * or the armorer surface.
     */
    public boolean giveKit(PlayerGateway player, GuardState state) {
        List<ItemSpec> stacks = ctx.policies().kits.get(state.rank);
        if (stacks == null || stacks.isEmpty()) return false;
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
        ctx.players().write(player.uuid(), state);
        player.tell("Echipamentul pentru " + ctx.policies().rankName(state.rank)
                + " a fost acordat.");
        return true;
    }
}
