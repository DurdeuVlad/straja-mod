package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A standing army formation bound to a station: the roster every military-track
 * member of that station joins through the Comandant desk. Self-organizing —
 * the first enlistment creates the unit, the first officer-grade member takes
 * command — so the army exists the moment soldiers do, without admin setup.
 * {@code readiness} is the unit's visible progression: successful operations
 * raise it, failed musters decay it.
 */
public class ArmyUnit {
    public String unitId = "";
    public String name = "";
    public String stationId = "hq";
    /** First officer-grade member to join; may be empty until an officer enlists. */
    public String commanderUuid = "";
    /** member uuid → enlistment timestamp. */
    public Map<String, Long> members = new LinkedHashMap<>();
    /** 0..100 — the unit's drill/turnout standing. */
    public int readiness = 50;
    public long operationsCompleted;
    public long operationsFailed;
    /** Round-robin cursor for the auto-scheduled operation kind. */
    public int nextAutoKind;
    /** When the next auto-scheduled operation should start (0 = now). */
    public long nextAutoAt;
    public long createdAt;
    public long version;

    public Map<String, Long> ensureMembers() {
        if (members == null) members = new LinkedHashMap<>();
        return members;
    }

    public boolean isMember(String playerUuid) {
        return playerUuid != null && ensureMembers().containsKey(playerUuid);
    }
}
