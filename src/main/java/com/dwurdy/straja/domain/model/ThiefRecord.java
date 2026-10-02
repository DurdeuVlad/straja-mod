package com.dwurdy.straja.domain.model;

/** Per-player debt ledger entry produced by theft from a protected storage zone. */
public record ThiefRecord(long owed, Integer repBackup, long flaggedAtMs) {
    public ThiefRecord {
        if (owed < 0) owed = 0;
    }

    public ThiefRecord addOwed(long amount) {
        if (amount <= 0) return this;
        return new ThiefRecord(owed + amount, repBackup, flaggedAtMs);
    }

    public ThiefRecord reducedBy(long amount) {
        if (amount <= 0) return this;
        return new ThiefRecord(Math.max(0, owed - amount), repBackup, flaggedAtMs);
    }

    public ThiefRecord withRepBackup(Integer rep) {
        return new ThiefRecord(owed, rep, flaggedAtMs);
    }
}
