package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class EquipmentIssue {
    public String issueId = "";
    public String playerUuid = "";
    public String stationId = "hq";
    public String sourceInstrumentId = "";
    public String mobilizationId = "";
    public List<Line> lines = new ArrayList<>();
    public long issuedAt;
    public EquipmentStatus status = EquipmentStatus.RESERVED;
    public String operationId = "";

    public enum EquipmentStatus { RESERVED, ISSUED, OUTSTANDING, PARTIALLY_RETURNED, RETURNED, FAILED }

    public static class Line {
        public String lineId = "";
        public String itemId = "";
        public long requestedQuantity;
        public long deliveredQuantity;
        public String assetId = "";
        public String fulfillmentStatus = "PENDING";
        public java.util.Map<String, Long> fulfillmentOperations = new java.util.LinkedHashMap<>();
    }
}
