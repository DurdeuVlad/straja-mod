package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class EvidenceStore {
    public int nextId = 1;
    public Map<String, EvidenceRecord> records = new LinkedHashMap<>();

    public String nextEvidenceId() {
        return "E-" + String.format(java.util.Locale.ROOT, "%05d", nextId++);
    }
}
