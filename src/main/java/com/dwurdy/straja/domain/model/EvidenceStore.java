package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class EvidenceStore {
    public int nextId = 1;
    public Map<String, EvidenceRecord> records = new LinkedHashMap<>();
    /** Durable retries for the physical reference items created by confiscation. */
    public Map<String, PendingDelivery> pendingDeliveries = new LinkedHashMap<>();

    public static class PendingDelivery {
        public String evidenceId = "";
        public String guardUuid = "";
        public String targetUuid = "";
        public boolean bagDelivered;
        public boolean receiptDelivered;
        public long createdAt;
    }

    public String nextEvidenceIdPreview() {
        return "E-" + String.format(java.util.Locale.ROOT, "%05d", nextId);
    }

    public String nextEvidenceId() {
        return "E-" + String.format(java.util.Locale.ROOT, "%05d", nextId++);
    }
}
