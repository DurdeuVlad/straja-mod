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
}
