package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complaint aggregate store. */
public class ComplaintStore {
    public int nextId = 1;
    public List<Complaint> complaints = new ArrayList<>();
    /** "dayWindow:issuerKey" -> coins reserved that day. */
    public Map<String, Integer> rewardBudgets = new LinkedHashMap<>();

    public Complaint find(String id) {
        for (Complaint c : complaints) if (c.id.equals(id)) return c;
        return null;
    }

    public String nextComplaintId() {
        return "C" + (nextId++);
    }
}
