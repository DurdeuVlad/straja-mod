package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class PromotionStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, PromotionApplication> applications = new LinkedHashMap<>();
    public Map<String, QualificationEvidence> evidence = new LinkedHashMap<>();
}
