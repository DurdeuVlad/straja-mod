package com.dwurdy.straja.domain.model;

/**
 * A prison sentence. Time is consumed only while the player is online and
 * active (moved within the AFK grace window); mirrors the reference schema.
 */
public class Sentence {
    public String id = "";
    public String target = "";
    public String targetUuid = "";
    public String fineId = "";
    public String missionId = "";
    public String arrestedBy = "";
    public String arrestedByUuid = "";
    public int sentenceDays = 1;
    /** Milliseconds of active (online, non-AFK) time still owed. */
    public long remainingActiveMs;
    /** WAITING_CELL | ACTIVE | SERVED | FORCED_RELEASE | CANCELLED */
    public String status = "WAITING_CELL";
    public long createdAt;
    public long lastTickAt;
    public long lastActivityAt;
    public long lastActivityNoticeAt;
    public Double lastX;
    public Double lastY;
    public Double lastZ;
    public String cellId = "";
    public Long servedAt;
    public String releaseReason = "";
}
