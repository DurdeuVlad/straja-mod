package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class BoloStore {
    public int nextId = 1;
    public List<BoloRecord> records = new ArrayList<>();

    public String nextBoloId() {
        return "BOLO-" + String.format(java.util.Locale.ROOT, "%04d", nextId++);
    }

    public BoloRecord find(String id) {
        if (id == null) return null;
        for (BoloRecord record : records) {
            if (record != null && id.equals(record.id)) return record;
        }
        return null;
    }
}
