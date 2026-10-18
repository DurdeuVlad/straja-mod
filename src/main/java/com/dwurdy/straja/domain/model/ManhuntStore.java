package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** FUN-7: durable manhunt aggregate. */
public class ManhuntStore {
    public int nextId = 1;
    public List<ManhuntRecord> hunts = new ArrayList<>();

    public String nextHuntId() { return "MH-" + (nextId++); }

    public ManhuntRecord find(String id) {
        if (id == null) return null;
        ensure();
        for (ManhuntRecord h : hunts) {
            if (h != null && id.equals(h.id)) return h;
        }
        return null;
    }

    public ManhuntRecord activeForRunner(String uuid) {
        ensure();
        if (uuid == null) return null;
        for (ManhuntRecord h : hunts) {
            if (h != null && "ACTIVE".equals(h.status) && h.runner(uuid)) return h;
        }
        return null;
    }

    /** Gson skips field initializers — normalize after every read. */
    public void ensure() {
        if (hunts == null) hunts = new ArrayList<>();
        for (ManhuntRecord h : hunts) {
            if (h == null) continue;
            if (h.runners == null) h.runners = new ArrayList<>();
            if (h.id == null) h.id = "";
            if (h.status == null) h.status = "ACTIVE";
            if (h.originLabel == null) h.originLabel = "";
            if (h.dimension == null) h.dimension = "minecraft:overworld";
            if (h.dispatchCallId == null) h.dispatchCallId = "";
            if (h.endedReason == null) h.endedReason = "";
        }
    }
}
