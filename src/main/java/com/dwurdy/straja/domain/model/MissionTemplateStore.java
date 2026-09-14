package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persisted mission-template register (§13). */
public class MissionTemplateStore {
    public Map<String, MissionTemplate> templates = new HashMap<>();
    public int nextId = 1;
    /** True once the canonical §13 defaults have been seeded — prevents reseeding after a deliberate wipe. */
    public boolean seeded;

    public MissionTemplate get(String id) {
        return id == null ? null : templates.get(id.trim());
    }

    public List<MissionTemplate> enabled() {
        List<MissionTemplate> out = new ArrayList<>();
        for (MissionTemplate t : templates.values()) {
            if (t != null && t.enabled) out.add(t);
        }
        out.sort((a, b) -> a.id.compareTo(b.id));
        return out;
    }
}
