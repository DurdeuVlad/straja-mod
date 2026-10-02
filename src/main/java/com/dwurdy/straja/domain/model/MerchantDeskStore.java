package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SavedData aggregate for merchant desks plus their trade ledger
 * (transactions are desk-scoped but stored flat for audit queries).
 */
public final class MerchantDeskStore {
    public static final int CURRENT_SCHEMA = 1;

    public int schemaVersion = CURRENT_SCHEMA;
    private Map<String, MerchantDeskRecord> desks;
    private List<TradeLedgerEntry> trades;

    public Map<String, MerchantDeskRecord> desks() {
        if (desks == null) desks = new LinkedHashMap<>();
        desks.values().removeIf(java.util.Objects::isNull);
        return desks;
    }

    public List<TradeLedgerEntry> trades() {
        if (trades == null) trades = new ArrayList<>();
        trades.removeIf(java.util.Objects::isNull);
        return trades;
    }

    public MerchantDeskRecord desk(String id) {
        return desks().get(id);
    }

    public void put(MerchantDeskRecord record) {
        desks().put(record.id, record);
    }

    public void remove(String id) {
        desks().remove(id);
    }

    /** Appends a trade entry, trimming oldest beyond {@code limit} (<=0 = unlimited). */
    public void appendTrade(TradeLedgerEntry entry, int limit) {
        trades().add(entry);
        if (limit > 0 && trades.size() > limit) {
            trades.subList(0, trades.size() - limit).clear();
        }
    }
}
