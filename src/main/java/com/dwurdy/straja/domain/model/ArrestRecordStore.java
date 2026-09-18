package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class ArrestRecordStore {
    public int nextId = 1;
    public List<ArrestRecord> records = new ArrayList<>();

    public String nextArrestId() {
        return "A-" + String.format(java.util.Locale.ROOT, "%04d", nextId++);
    }

    public ArrestRecord find(String id) {
        if (id == null) return null;
        for (ArrestRecord record : records) {
            if (record != null && id.equals(record.id)) return record;
        }
        return null;
    }
}
