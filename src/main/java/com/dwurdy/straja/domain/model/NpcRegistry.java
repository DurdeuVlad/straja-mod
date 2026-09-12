package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent registry of Straja NPCs: entity UUID -> record. */
public class NpcRegistry {
    public Map<String, Record> npcs = new LinkedHashMap<>();

    public static class Record {
        public String entityUuid = "";
        public String role = "receptionist";
        public String displayName = "";
        public String skin = "";
        public long createdAt;
    }

    public Record byUuid(String entityUuid) {
        return npcs.get(entityUuid);
    }
}
