package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class DocumentStore {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, DocumentRecord> documents = new LinkedHashMap<>();
    public Map<String, DocumentInstrument> instruments = new LinkedHashMap<>();
    public Map<String, DocumentRedemption> redemptions = new LinkedHashMap<>();
    public Map<String, String> idempotencyIndex = new LinkedHashMap<>();
    public Map<String, FormRequest> formRequests = new LinkedHashMap<>();
    public Map<String, String> formRequestIndex = new LinkedHashMap<>();
    /** Re-creates every collection after a corrupt or partial read — the
     *  single list of what this store owns, shared by migration paths. */
    public void ensureCollections() {
        ensureDocuments();
        ensureInstruments();
        ensureRedemptions();
        ensureIdempotencyIndex();
        ensureFormRequests();
        ensureFormRequestIndex();
    }

    /** Lazily re-creates {@code documents} after a corrupt or partial read. */
    public Map<String, DocumentRecord> ensureDocuments() {
        if (documents == null) documents = new LinkedHashMap<>();
        return documents;
    }

    /** Lazily re-creates {@code instruments} after a corrupt or partial read. */
    public Map<String, DocumentInstrument> ensureInstruments() {
        if (instruments == null) instruments = new LinkedHashMap<>();
        return instruments;
    }

    /** Lazily re-creates {@code redemptions} after a corrupt or partial read. */
    public Map<String, DocumentRedemption> ensureRedemptions() {
        if (redemptions == null) redemptions = new LinkedHashMap<>();
        return redemptions;
    }

    /** Lazily re-creates {@code idempotencyIndex} after a corrupt or partial read. */
    public Map<String, String> ensureIdempotencyIndex() {
        if (idempotencyIndex == null) idempotencyIndex = new LinkedHashMap<>();
        return idempotencyIndex;
    }

    /** Lazily re-creates {@code formRequests} after a corrupt or partial read. */
    public Map<String, FormRequest> ensureFormRequests() {
        if (formRequests == null) formRequests = new LinkedHashMap<>();
        return formRequests;
    }

    /** Lazily re-creates {@code formRequestIndex} after a corrupt or partial read. */
    public Map<String, String> ensureFormRequestIndex() {
        if (formRequestIndex == null) formRequestIndex = new LinkedHashMap<>();
        return formRequestIndex;
    }

}
