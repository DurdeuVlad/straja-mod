package com.dwurdy.straja.domain.model;

/**
 * Reusable order-book draft held by an issuer. Mirrors the reference schema:
 * one draft per issuer, signed then sealed, with copy/pool budgets.
 */
public class MissionDraft {
    public String issuer = "";
    public String issuerUuid = "";
    public String objective = "";
    public int minutes;
    public int reward;
    public int maxCopies = 1;
    public int rewardPool;
    public int minimumRank = 1;
    public int maxAssignees = 1;
    public int rewardCommitted;
    public int remainingRewardPool;
    public long startAt;
    public String startLabel = "acum";
    public String signedBy = "";
    public Long signedAt;
    public Long packagedAt;
    public int issuedCount;
    public String lastMissionId = "";
}
