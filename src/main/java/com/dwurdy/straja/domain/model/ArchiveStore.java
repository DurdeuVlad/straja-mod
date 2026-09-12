package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-side archive: folders, sheets, catalog, pending deliveries. */
public class ArchiveStore {
    public int nextFolderNumber = 1;
    public int nextSheetNumber = 1;
    public int nextCatalogNumber = 1;
    public int nextCopyNumber = 1;
    /** Authorized archivists: player uuid -> display name. */
    public Map<String, String> archivists = new LinkedHashMap<>();
    public Map<String, Folder> folders = new LinkedHashMap<>();
    public Map<String, Sheet> sheets = new LinkedHashMap<>();
    public Map<String, CatalogEntry> catalog = new LinkedHashMap<>();
    public List<Copy> copies = new ArrayList<>();
    public List<Operation> operations = new ArrayList<>();
    public List<PendingDelivery> pendingDeliveries = new ArrayList<>();

    /** A numbered copy (or official-envelope send) of a signed sheet. */
    public static class Copy {
        public String id = "";           // CP-<n>
        public String originalId = "";
        public String folderId = "";
        public Integer copyNumber;
        public Integer totalCopies;
        public String recipientName = "";
        public String recipientKey = "";
        /** Pinned UUID when the recipient was resolvable at creation; empty for
         *  name-only legacy/offline recipients (matched by name as fallback). */
        public String recipientUuid = "";
        public String issuerName = "";
        public String issuerKey = "";
        public String kind = "COPY";     // COPY | ENVELOPE
        /** PREPARED | CONSUMING | CONSUMED | NOT_CONSUMED (envelope source). */
        public String sourceState = "";
        /** PENDING | DELIVERED | FAILED */
        public String status = "PENDING";
        public String deliveryError = "";
        public long createdAt;
        public Long deliveredAt;
        public Long sourceConsumeStartedAt;
        public Long sourceConsumedAt;
    }

    /** Journal of multi-item operations (carbon copy runs). */
    public static class Operation {
        public String id = "";
        public String kind = "COPY";
        /** PREPARED | CONSUMED | ABORTED | COMPLETED | COMPLETED_WITH_PENDING */
        public String status = "PREPARED";
        public String actor = "";
        public String sheetId = "";
        public int count;
        public long createdAt;
        public Long completedAt;
    }

    public static class Folder {
        public String id = "";
        public String title = "";
        public String department = "";
        public String owner = "";
        public String ownerUuid = "";
        public String status = "OPEN"; // OPEN | CLOSED (read-only)
        public long createdAt;
        public List<String> sheetIds = new ArrayList<>();
        public List<String> catalogIds = new ArrayList<>();
    }

    public static class Sheet {
        public String id = "";           // A-<n>
        public String folderId = "";
        public String type = "NOTĂ";
        public String title = "";
        public String content = "";
        public String author = "";
        public String authorUuid = "";
        public String authorKey = "";
        /** DRAFT | PENDING_SIGNATURE | SIGNED | REVOKED */
        public String status = "DRAFT";
        public int revision = 1;
        public String originId;          // set on copy-sheets
        public List<Recipient> recipients = new ArrayList<>();
        public Long submittedAt;
        public Long signedAt;
        public Signer signedBy;
        public String signatureReason = "";
        public long createdAt;
        public long updatedAt;
        public boolean revoked;
    }

    public static class Recipient {
        public String name = "";
        public String key = "";
    }

    public static class Signer {
        public String key = "";
        public String uuid = "";
        public String name = "";
    }

    public static class CatalogEntry {
        public String id = "";
        public String folderId = "";
        public String alias = "";
        public String fingerprint = "";
        public long addedAt;
    }

    public static class PendingDelivery {
        public String id = "";
        public String target = "";
        public String targetUuid = "";
        public String kind = "";           // COPY | ENVELOPE
        public String sheetId = "";
        public int copyNumber;
        public String status = "PENDING";  // PENDING | CONSUMING | DELIVERED | DELIVERY_FAILED
        public long createdAt;
        public int attempts;
    }
}
