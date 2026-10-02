package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One merchant-desk transaction (AT5): what was sold, by whom, and how much
 * base currency was disbursed in coins or credited to a labor account.
 */
public class TradeLedgerEntry {
    public String id = "";
    public long timestamp;
    public String deskId = "";
    public String sellerUuid = "";
    public String sellerName = "";
    /** item id -> count actually sold. */
    public Map<String, Integer> itemsSold = new LinkedHashMap<>();
    /** Base currency units (bronze = 1) disbursed or credited. */
    public int baseUnits;
    /** True when the value went to a penal labor account instead of coins. */
    public boolean creditedToLabor;

    public TradeLedgerEntry() {}

    public TradeLedgerEntry(String id, long timestamp, String deskId, String sellerUuid) {
        this.id = id;
        this.timestamp = timestamp;
        this.deskId = deskId;
        this.sellerUuid = sellerUuid;
    }
}
