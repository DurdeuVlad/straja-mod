package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prison aggregate: cells, per-cell assignments, waitlist, sentences. */
public class PrisonStore {
    public List<Cell> cells = new ArrayList<>();
    public Map<String, Assignment> assignments = new LinkedHashMap<>(); // cellId -> assignment
    public List<WaitlistEntry> waitlist = new ArrayList<>();
    public List<Sentence> sentences = new ArrayList<>();
    /** Commissioner marker selections for cell discovery. */
    public Map<String, RoomStore.MarkerSelection> selections = new LinkedHashMap<>();

    public static class WaitlistEntry {
        public String sentenceId = "";
        public String target = "";
        public String targetUuid = "";
        public long requestedAt;
    }

    public static class Assignment {
        public String target = "";
        public String targetUuid = "";
        public String sentenceId = "";
        public long assignedAt;
    }

    public Sentence activeSentenceFor(String key) {
        if (key == null) return null;
        for (int i = sentences.size() - 1; i >= 0; i--) {
            Sentence s = sentences.get(i);
            if (s == null) continue;
            boolean matches = s.targetUuid != null && !s.targetUuid.isEmpty()
                    ? s.targetUuid.equals(key)
                    : s.target != null && s.target.equalsIgnoreCase(key);
            if (matches && ("WAITING_CELL".equals(s.status) || "ACTIVE".equals(s.status))) return s;
        }
        return null;
    }

    public Cell cell(String id) {
        if (id == null) return null;
        for (Cell c : cells) if (c != null && id.equals(c.id)) return c;
        return null;
    }
}
