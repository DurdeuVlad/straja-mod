package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One authoritative seized stack; the physical bag is only a reference. */
public class EvidenceRecord {
    public String id = "";
    public String sourcePlayerUuid = "";
    public String sourcePlayerName = "";
    public String seizingGuardUuid = "";
    public String seizingGuardName = "";
    public String itemId = "";
    public String itemName = "";
    public int amount;
    public int maxStackSize = 64;
    public Map<String, String> itemData = new LinkedHashMap<>();
    public long seizedAt;
    public String reason = "";
    public String incidentId = "";
    public String caseId = "";
    public EvidenceStatus status = EvidenceStatus.SEIZED;
    public String currentCustodianUuid = "";
    public String currentCustodianName = "";
    public String currentLocation = "";
    public List<EvidenceCustodyEvent> custodyHistory = new ArrayList<>();
}
