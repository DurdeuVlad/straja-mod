package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * Inbound port for the Armorer NPC shop. Native surfaces list the offers a
 * player can see and dispatch purchases by offer key; every mutating call
 * revalidates rank, funds, and delivery server-side.
 */
public interface ArmoryUseCase {

    /** A purchasable offer. {@code cost} is Bronze coins for stock items and
     * requisition points for reserves. */
    record Offer(String key, String itemId, int count, int cost, int minRank, boolean reserve) {}

    /** Offers visible to this player's rank (empty when ineligible). */
    List<Offer> offers(PlayerGateway player);

    /** Coin purchase from the stock shelf. */
    boolean buy(PlayerGateway player, String key);

    /** Requisition-point purchase from the reserve shelf. */
    boolean buyReserve(PlayerGateway player, String key);
}
