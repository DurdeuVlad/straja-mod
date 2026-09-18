package com.dwurdy.straja.domain.model;

public class ReputationEvent {
    public String id = "";
    public String idempotencyKey = "";
    public String playerUuid = "";
    public String playerName = "";
    public String sourceType = "";
    public String sourceRecordId = "";
    public int delta;
    public int scoreBefore;
    public int scoreAfter;
    public long at;
    public String actorUuid = "";
    public String actorName = "";
    public String reason = "";
    public boolean cappedAtNeutral;
    public boolean voided;
    public String reversalOf = "";
}
