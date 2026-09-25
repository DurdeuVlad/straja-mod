package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class MobilizationOrder {
    public String mobilizationId = "";
    public String specialistUuid = "";
    public String missionId = "";
    public String campaignId = "";
    public String stationId = "hq";
    public String jurisdiction = "";
    public String authorizedBy = "";
    public String authorizationSource = "";
    public long startsAt;
    public long expiresAt;
    public Long musteredAt;
    public Long demobilizedAt;
    public MobilizationStatus status = MobilizationStatus.AUTHORIZED;
    public List<String> equipmentIssueIds = new ArrayList<>();
    public String settlementId = "";
    public long settlementAmount;
    public long version;

    public enum MobilizationStatus { AUTHORIZED, MUSTERED, ACTIVE, DEMOBILIZING, COMPLETED, CANCELLED, EXPIRED }

    public boolean activeAt(long now) {
        return status == MobilizationStatus.ACTIVE && now < expiresAt;
    }
}
