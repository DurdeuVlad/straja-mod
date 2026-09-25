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
}
