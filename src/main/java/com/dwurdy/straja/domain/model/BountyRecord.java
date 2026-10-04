package com.dwurdy.straja.domain.model;

/**
 * A state-issued contract on a wanted player. Only ranks >= Inspector may
 * post; any player may hunt; the state pays the capturing hunter from the
 * mint and the prisoner is fined {@code amount * fineMultiplier}, payable as
 * physical-coin bail (by anyone) in exchange for early release.
 */
public class BountyRecord {
    public String id = "";
    public String targetUuid = "";
    public String targetName = "";
    public int amount;
    public String postedBy = "";
    public String postedByUuid = "";
    public int postedByRank;
    public String reason = "";
    public long postedAt;
    public long expiresAt;
    public BountyStatus status = BountyStatus.ACTIVE;
    public String linkedBoloId = "";
    public String linkedFineId = "";
    public String hunterUuid = "";
    public String hunterName = "";
    public long capturedAt;
    /** Coins minted but not yet delivered (hunter offline at capture). */
    public boolean payoutPending;
    /** Bail window end (epoch ms); on lapse unpaid -> camp transfer. */
    public long bailDeadlineAt;
    /** Camp transfer already attempted after the bail window lapsed. */
    public boolean campTransferAttempted;
}
