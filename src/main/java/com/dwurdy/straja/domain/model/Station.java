package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Station {
    public String stationId = "";
    public String displayName = "";
    public boolean enabled = true;
    public List<String> jurisdictions = new ArrayList<>();
    public Map<String, SetupData.Location> locationsByRole = new LinkedHashMap<>();
    public Map<String, String> npcBindings = new LinkedHashMap<>();
    public Map<String, Long> budgetAccounts = new LinkedHashMap<>();
    public Map<String, Long> inventoryAccounts = new LinkedHashMap<>();
    public List<String> missionPools = new ArrayList<>();
    public String fallbackStationId = "";
    public String messageTemplateSetId = "default";
    /** Player-facing message templates; values may contain the documented station placeholders. */
    public Map<String, String> messageTemplates = new LinkedHashMap<>();
    public boolean headquarters;

    public boolean serves(String jurisdiction) {
        return jurisdiction == null || jurisdiction.isBlank() || jurisdictions.isEmpty()
                || jurisdictions.contains(jurisdiction);
    }
}
