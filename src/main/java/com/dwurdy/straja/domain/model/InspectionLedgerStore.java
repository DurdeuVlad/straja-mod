package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * SavedData aggregate for the inspection ledger. Entries are append-only;
 * {@link #append(InspectionLedgerEntry, int)} trims the oldest beyond the
 * configured retention limit (snapshots make entries heavy, so the cap
 * bounds memory/disk — prototype used 2000; native default is higher).
 */
public final class InspectionLedgerStore {
    public static final int CURRENT_SCHEMA = 1;

    public int schemaVersion = CURRENT_SCHEMA;
    private List<InspectionLedgerEntry> entries;

    public List<InspectionLedgerEntry> entries() {
        if (entries == null) entries = new ArrayList<>();
        entries.removeIf(java.util.Objects::isNull);
        return entries;
    }

    /** Appends and trims to {@code limit} (<=0 keeps everything). */
    public void append(InspectionLedgerEntry entry, int limit) {
        entries().add(entry);
        if (limit > 0 && entries.size() > limit) {
            entries.subList(0, entries.size() - limit).clear();
        }
    }
}
