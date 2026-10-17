package com.dwurdy.straja.domain.model;

import java.util.HashMap;

/** Pure rules for the §11 reporting interval. */
public final class ActivityReports {
    private ActivityReports() {}

    /**
     * Stamps the member's period anchor when absent; returns true when the
     * store changed and must be persisted. A fresh member's first interval
     * starts at first contact — they can never be instantly overdue.
     */
    public static boolean ensureAnchor(ActivityReportStore store, String uuid, long nowMillis) {
        if (store.anchors.containsKey(uuid)) return false;
        store.anchors.put(uuid, nowMillis);
        return true;
    }

    /** Deadline for the member's next report: anchor + interval. */
    public static long dueAt(ActivityReportStore store, String uuid, long nowMillis, long intervalMillis) {
        ensureAnchor(store, uuid, nowMillis);
        return store.anchors.get(uuid) + intervalMillis;
    }

    /** Overdue when the current interval's deadline has passed. */
    public static boolean isOverdue(ActivityReportStore store, String uuid, long nowMillis, long intervalMillis) {
        return nowMillis > dueAt(store, uuid, nowMillis, intervalMillis);
    }

    /**
     * Stamps the member's service-block snapshot when absent so report
     * windows measure a delta, not the lifetime total. Returns true when
     * the store changed.
     */
    public static boolean ensureBlockSnapshot(ActivityReportStore store, String uuid, long serviceBlocks) {
        if (store.blockSnapshots == null) store.blockSnapshots = new HashMap<>();
        if (store.blockSnapshots.containsKey(uuid)) return false;
        store.blockSnapshots.put(uuid, serviceBlocks);
        return true;
    }

    /** Blocks earned inside the member's current reporting window. */
    public static long blocksInWindow(ActivityReportStore store, String uuid, long serviceBlocks) {
        if (store.blockSnapshots == null) return serviceBlocks;
        Long snapshot = store.blockSnapshots.get(uuid);
        return Math.max(0L, serviceBlocks - (snapshot == null ? 0L : snapshot));
    }
}
