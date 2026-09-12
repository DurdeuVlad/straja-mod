package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custody-related persistent state: cuff requests, cuffed players, rope-bound
 * players, head sacks, downed players and pending cuff keys.
 * Field names mirror the reference KubeJS schema for migration.
 */
public class CustodyStore {
    public int nextRequestId = 1;
    public Map<String, CuffRequest> cuffRequests = new LinkedHashMap<>();
    public Map<String, CuffRecord> cuffed = new LinkedHashMap<>();
    public Map<String, BoundRecord> bound = new LinkedHashMap<>();
    public Map<String, HeadSackRecord> headSacks = new LinkedHashMap<>();
    public Map<String, DownedRecord> downed = new LinkedHashMap<>();
    /** holderKey -> count of cuff keys awaiting delivery. */
    public Map<String, Integer> pendingKeys = new LinkedHashMap<>();
    /** playerKey -> items withheld at release because the inventory was full. */
    public Map<String, List<PendingItem>> pendingItems = new LinkedHashMap<>();

    public static class CuffRequest {
        public String id = "";
        public String kind = "CUFF"; // CUFF | SURRENDER
        public String issuer = "";
        public String issuerUuid = "";
        public String target = "";
        public String targetUuid = "";
        public String targetKey = "";
        public int issuerRank;
        public String issuerCapability = "useCuffs";
        public long createdAt;
        public long expiresAt;
    }

    public static class CuffRecord {
        public String target = "";
        public String targetUuid = "";
        public String issuer = "";
        public String issuerUuid = "";
        public long cuffedAt;
        public double maxDistance = 32;
        public Long outOfRangeAt;
        public String reason = "";
        public int originalSelectedSlot = -1;
        /** The held item hidden while cuffed, restored on release. */
        public String hiddenItemId = "";
        public int hiddenItemCount;
        public Map<String, String> hiddenItemData = new LinkedHashMap<>();
        public String hiddenItemName = "";
    }

    /** A hidden cuffed-hand stack that could not be returned at release time. */
    public static class PendingItem {
        public String itemId = "";
        public int count;
        public Map<String, String> data = new LinkedHashMap<>();
        public String name = "";
    }

    public static class BoundRecord {
        public String target = "";
        public String targetUuid = "";
        public String issuer = "";
        public String issuerUuid = "";
        public long boundAt;
        public String reason = "criminal_transport";
        public long lastBlockedNoticeAt;
    }

    public static class HeadSackRecord {
        public String target = "";
        public String targetUuid = "";
        public String issuer = "";
        public String issuerUuid = "";
        public long appliedAt;
    }

    public static class DownedRecord {
        public String target = "";
        public String targetUuid = "";
        public String dimension = "minecraft:overworld";
        public double x, y, z;
        public long startedAt;
        public long wakesAt;
        public String source = "";
        public String sourceUuid = "";
        public String reason = "";
        public String status = "UNCONSCIOUS"; // UNCONSCIOUS | TRANSPORTED
        public String destination = "";
        public long lastBlockedNoticeAt;
    }
}
