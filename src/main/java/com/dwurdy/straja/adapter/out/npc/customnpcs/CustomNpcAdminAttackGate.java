package com.dwurdy.straja.adapter.out.npc.customnpcs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The server attack decision, separate from the optional CustomNPCs GUI API. */
final class CustomNpcAdminAttackGate {
    static final String CUSTOM_NPC_TYPE = "customnpcs:customnpc";

    enum Action { PASS, CANCEL, OPEN }

    record Decision(Action action, UUID hostId) {}

    private final Map<UUID, Long> lastOpenedTick = new HashMap<>();

    Decision decide(UUID playerId, UUID hostId, String targetType, boolean liveTarget,
                    boolean operatorWithWand, boolean providerAvailable, long gameTime) {
        if (!operatorWithWand || !CUSTOM_NPC_TYPE.equals(targetType)) {
            return new Decision(Action.PASS, null);
        }
        if (!liveTarget || hostId == null) {
            return new Decision(Action.CANCEL, hostId);
        }
        if (!providerAvailable) {
            return new Decision(Action.CANCEL, hostId);
        }
        Long previous = lastOpenedTick.get(playerId);
        if (previous != null && gameTime >= previous && gameTime - previous <= 1) {
            return new Decision(Action.CANCEL, hostId);
        }
        lastOpenedTick.put(playerId, gameTime);
        return new Decision(Action.OPEN, hostId);
    }

    void forget(UUID playerId) {
        lastOpenedTick.remove(playerId);
    }
}
