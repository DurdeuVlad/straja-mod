package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SavedData aggregate for law checkpoints. Holds the per-site records plus
 * the global policy maps (illegal items, bans, exemptions) that the
 * prototype kept top-level in {@code portCheckpointCfg}.
 */
public final class LawCheckpointStore {
    /** Bumped when the schema changes; readers must tolerate older versions. */
    public static final int CURRENT_SCHEMA = 1;

    public int schemaVersion = CURRENT_SCHEMA;
    private Map<String, LawCheckpointRecord> checkpoints;

    /** item id -> true (globally illegal). */
    private Map<String, Boolean> globalIllegalItems;
    /** Globally banned player names/UUIDs. */
    private List<String> globalBans;
    /** Globally exempt player names/UUIDs. */
    private List<String> globalExemptions;

    public Map<String, LawCheckpointRecord> checkpoints() {
        if (checkpoints == null) checkpoints = new LinkedHashMap<>();
        checkpoints.values().removeIf(java.util.Objects::isNull);
        return checkpoints;
    }

    public Map<String, Boolean> globalIllegalItems() {
        if (globalIllegalItems == null) globalIllegalItems = new LinkedHashMap<>();
        return globalIllegalItems;
    }

    public List<String> globalBans() {
        if (globalBans == null) globalBans = new ArrayList<>();
        return globalBans;
    }

    public List<String> globalExemptions() {
        if (globalExemptions == null) globalExemptions = new ArrayList<>();
        return globalExemptions;
    }

    public LawCheckpointRecord checkpoint(String id) {
        return checkpoints().get(id);
    }

    public void put(LawCheckpointRecord record) {
        checkpoints().put(record.id, record);
    }

    public void remove(String id) {
        checkpoints().remove(id);
    }

    /** Effective contraband check: site-local override wins, else global. */
    public boolean isIllegal(LawCheckpointRecord site, String itemId) {
        if (site != null) {
            if (site.localAllowedItems.contains(itemId)) return false;
            if (site.localIllegalItems.contains(itemId)) return true;
        }
        return Boolean.TRUE.equals(globalIllegalItems().get(itemId));
    }
}
