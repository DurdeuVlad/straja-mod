package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #242 guided tester protocol. One entry per enrolled tester, keyed by
 * player-uuid string; progress survives relogs and restarts so a tester can
 * resume mid-run. {@code chapter} is the current chapter index (0 = dossier
 * cover issued, chapters are 1..N); {@code finished} marks a completed run.
 */
public class ProtocolStore {
    public Map<String, Entry> testers = new LinkedHashMap<>();

    public static class Entry {
        public String playerUuid = "";
        public String playerName = "";
        /** Current chapter index (0-based). -1 = enrolled but dossier pending. */
        public int chapter = -1;
        public boolean finished;
        /** Uuid of the spawned mock suspect, when one is live. */
        public String actorUuid = "";
        /** Bitmask of chapter hooks already run — back/next never re-fires. */
        public int hooksDone;
        public long startedAt;
        public long updatedAt;
    }

    public Entry find(String playerUuid) {
        return playerUuid == null ? null : testers.get(playerUuid);
    }

    public Entry enroll(String playerUuid, String playerName, long now) {
        Entry entry = new Entry();
        entry.playerUuid = playerUuid;
        entry.playerName = playerName == null ? "" : playerName;
        entry.chapter = 0;
        entry.startedAt = now;
        entry.updatedAt = now;
        testers.put(playerUuid, entry);
        return entry;
    }
}
