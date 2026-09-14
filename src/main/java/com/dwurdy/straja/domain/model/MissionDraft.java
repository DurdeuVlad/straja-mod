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
    // §13 calculated-budget metadata: when set, the draft originated from a
    // template and reward was derived from the wage table at creation.
    public String templateId = "";
    public double estimatedHours;
    public double risk = 1.0;
    /** Set when the issuer overrode the calculated reward; audited. */
    public String overrideReason = "";
    /** §13 template flag — issued copies substitute patrol duty. */
    public boolean supersedesPatrol;
}
