package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class CampaignStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, MissionCampaign> campaigns = new LinkedHashMap<>();
    public Map<String, QuotaReservation> reservations = new LinkedHashMap<>();

    /** Lazily re-creates {@code campaigns} after a corrupt or partial read. */
    public Map<String, MissionCampaign> ensureCampaigns() {
        if (campaigns == null) campaigns = new LinkedHashMap<>();
        return campaigns;
    }
}
