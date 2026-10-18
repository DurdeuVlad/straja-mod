package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class PromotionStore {
    /** v2: remodelled career ladder — legacy enum names remap at parse time. */
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, PromotionApplication> applications = new LinkedHashMap<>();
    public Map<String, QualificationEvidence> evidence = new LinkedHashMap<>();
    /** Lazily re-creates {@code applications} after a corrupt or partial read. */
    public Map<String, PromotionApplication> ensureApplications() {
        if (applications == null) applications = new LinkedHashMap<>();
        return applications;
    }

    /**
     * Gson skips field initializers — applications serialized before the
     * petition-model fields existed read them back as null. Called on every
     * store read so older saves cannot NPE later code.
     */
    public void normalize() {
        ensureApplications();
        for (PromotionApplication application : applications.values()) {
            if (application == null) continue;
            if (application.applicationId == null) application.applicationId = "";
            if (application.subjectUuid == null) application.subjectUuid = "";
            if (application.petitionReason == null) application.petitionReason = "";
            if (application.decisionReason == null) application.decisionReason = "";
            if (application.reviewedBy == null) application.reviewedBy = "";
            if (application.personnelSyncState == null) application.personnelSyncState = "PENDING";
            if (application.evidenceIds == null) application.evidenceIds = new java.util.ArrayList<>();
            if (application.examAttemptIds == null) application.examAttemptIds = new java.util.ArrayList<>();
            if (application.outboundIntents == null)
                application.outboundIntents = new java.util.ArrayList<>();
        }
    }

}
