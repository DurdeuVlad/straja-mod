package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class QuotaReservation {
    public String reservationId = "";
    public String campaignId = "";
    public String missionId = "";
    public String playerUuid = "";
    public long reservedQuantity;
    public long fulfilledQuantity;
    public long expiresAt;
    public ReservationStatus status = ReservationStatus.RESERVED;
    public String operationId = "";
    /** Operation key -> quantity already accepted under that key. */
    public Map<String, Long> fulfillmentOperations = new LinkedHashMap<>();
    public long version;

    public enum ReservationStatus { RESERVED, PARTIALLY_FULFILLED, FULFILLED, RELEASED, EXPIRED }
}
