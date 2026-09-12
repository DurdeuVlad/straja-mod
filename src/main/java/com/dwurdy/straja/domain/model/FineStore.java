package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fine aggregate store: fines, recovery/warrant tasks, appeal abuse counters. */
public class FineStore {
    public int nextId = 1;
    public List<Fine> fines = new ArrayList<>();
    public List<FineTask> tasks = new ArrayList<>();
    public Map<String, AppealAbuse> appealAbuse = new LinkedHashMap<>();

    /** Per-issuer fine forms ("Registrul de Amenzi" drafts), keyed by issuer uuid. */
    public Map<String, FineDraft> drafts = new LinkedHashMap<>();

    public static class AppealAbuse {
        public List<Long> attempts = new ArrayList<>();
        public long blockedUntil;
    }

    public static class FineDraft {
        public String target = "";
        public String targetUuid = "";
        public int amount;
        public String law = "";
        public String description = "";
        public long writtenAt;
    }

    public String nextFineId() {
        return "F" + (nextId++);
    }

    public Fine find(String id) {
        for (Fine fine : fines) if (fine.id.equals(id)) return fine;
        return null;
    }

    public FineTask findTask(String id) {
        for (FineTask task : tasks) if (task.id.equals(id)) return task;
        return null;
    }
}
