package com.dwurdy.straja.domain.model;

public class EquipmentObligation {
    public String obligationId = "";
    public String playerUuid = "";
    public String assetId = "";
    public String itemId = "";
    public long issuedQuantity;
    public long outstandingQuantity;
    public String responsibility = "";
    public String issuedCondition = "";
    public String returnedCondition = "";
    public long returnedQuantity;
    public String returnProofDocumentId = "";
    public EquipmentStatus status = EquipmentStatus.OUTSTANDING;
    public long debtAmount;
    public String waiverId = "";

    public enum EquipmentStatus {
        OUTSTANDING, PARTIALLY_RETURNED, RETURNED, LOST, DESTROYED,
        DEBT_PENDING, DEBT_SETTLED, WAIVED, LEGACY_OWNED
    }
}
