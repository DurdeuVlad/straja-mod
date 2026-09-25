package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class MissionCampaign {
    public String campaignId = "";
    public String type = "";
    public String stationId = "hq";
    public String jurisdiction = "";
    public CampaignStatus status = CampaignStatus.DRAFT;
    public long startsAt;
    public long globalDeadline;
    public long globalQuota;
    public long quantityReserved;
    public long quantityAccepted;
    public String orderDeadlinePolicy = "";
    public List<String> eligibleProfessions = new ArrayList<>();
    public String predecessorCampaignId = "";
    public String rewardPolicy = "";
    public String cancellationReason = "";
    public long version;

    public enum CampaignStatus { DRAFT, SCHEDULED, ACTIVE, CLOSING, COMPLETED, CANCELLED, EXPIRED }

    public boolean canReserve(long quantity) {
        if (quantity <= 0 || status != CampaignStatus.ACTIVE
                || quantityAccepted < 0 || quantityReserved < 0
                || quantityAccepted > globalQuota) return false;
        long remainingAfterAccepted = globalQuota - quantityAccepted;
        return quantityReserved <= remainingAfterAccepted
                && quantity <= remainingAfterAccepted - quantityReserved;
    }
}
