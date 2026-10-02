package com.dwurdy.straja.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Optional bridge to a guard NPC provider (CustomNPCs today). Everything the
 * storage watch needs from NPCs — faction reputation reads, quest dispatch,
 * and aggro steering — funnels through this port so the service compiles and
 * tests without the NPC mod present.
 */
public interface NpcGuardGateway {
    /** A guard entity handle with its position, for distance/los checks. */
    record GuardRef(UUID id, double x, double y, double z) {}

    boolean available();

    /** Faction points for a player, or {@code null} when the read is impossible. */
    Integer factionPoints(UUID playerId, int factionId);

    /** Sets a player's faction points (console command behind the port). */
    void setFactionPoints(UUID playerId, int factionId, int points);

    /** Starts the hunt quest on every member of a scoreboard team. */
    void startQuestForTeam(String teamName, int questId);

    /** Starts the hunt quest on a single player (mid-incident login). */
    void startQuestForPlayer(UUID playerId, int questId);

    /** Marks the hunt quest finished for every member of a scoreboard team. */
    void finishQuestForTeam(String teamName, int questId);

    /** Guard entities within {@code radius} of the point, filtered to the faction. */
    List<GuardRef> guardsNear(String dimension, double x, double y, double z, double radius, int factionId);

    boolean hasLineOfSight(UUID guardId, UUID playerId);

    /** NPC-configured aggro range, or {@code fallback} when unreadable. */
    int aggroRange(UUID guardId, int fallback);

    void setTarget(UUID guardId, UUID playerId);

    /** Clears the guard's target only when it currently targets {@code playerId}. */
    void clearTargetIfTargeting(UUID guardId, UUID playerId);

    /** No-op implementation for environments without a guard NPC provider. */
    static NpcGuardGateway disabled() {
        return DisabledNpcGuardGateway.INSTANCE;
    }
}

final class DisabledNpcGuardGateway implements NpcGuardGateway {
    static final DisabledNpcGuardGateway INSTANCE = new DisabledNpcGuardGateway();
    private DisabledNpcGuardGateway() {}

    @Override public boolean available() { return false; }
    @Override public Integer factionPoints(UUID playerId, int factionId) { return null; }
    @Override public void setFactionPoints(UUID playerId, int factionId, int points) {}
    @Override public void startQuestForTeam(String teamName, int questId) {}
    @Override public void startQuestForPlayer(UUID playerId, int questId) {}
    @Override public void finishQuestForTeam(String teamName, int questId) {}
    @Override public List<GuardRef> guardsNear(String dimension, double x, double y, double z, double radius, int factionId) {
        return List.of();
    }
    @Override public boolean hasLineOfSight(UUID guardId, UUID playerId) { return false; }
    @Override public int aggroRange(UUID guardId, int fallback) { return fallback; }
    @Override public void setTarget(UUID guardId, UUID playerId) {}
    @Override public void clearTargetIfTargeting(UUID guardId, UUID playerId) {}
}
