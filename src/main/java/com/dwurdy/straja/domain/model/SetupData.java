package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** World-dependent configuration: checkpoints, named locations, mission times. */
public class SetupData {
    public List<Checkpoint> checkpoints = new ArrayList<>();
    public Map<String, Location> locations = new LinkedHashMap<>();
    public Map<String, Integer> missionMinutes = new LinkedHashMap<>();

    /**
     * Fresh setup seeds the four reference checkpoint slots, all unplaced.
     * Admins grow or shrink the route with add/remove — the list is not
     * fixed at four.
     */
    public static SetupData defaults() {
        SetupData data = new SetupData();
        for (int i = 1; i <= 4; i++) {
            data.checkpoints.add(newCheckpoint("checkpoint_" + i));
        }
        return data;
    }

    public static Checkpoint newCheckpoint(String id) {
        var point = new Checkpoint();
        point.id = id;
        point.name = "Checkpoint " + id.substring(id.lastIndexOf('_') + 1);
        return point;
    }

    /** Next free {@code checkpoint_N} id — lowest unused index keeps ids stable. */
    public String nextCheckpointId() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Checkpoint c : checkpoints) ids.add(c.id);
        int i = 1;
        while (ids.contains("checkpoint_" + i)) i++;
        return "checkpoint_" + i;
    }

    public static final String HQ = "hq";

    public static final String[] LOCATION_KEYS = {
            "reportsLectern", "commissionerMailbox", "commissionerOffice",
            "receptionist", "secretary", "trainer", "armorer", "prisonRelease", "infirmary",
            HQ
    };

    /** Canonical setup location used when spawning one of the four physical NPCs. */
    public static String npcLocationKey(String role) {
        return switch (role == null ? "" : role.trim().toLowerCase()) {
            case "receptionist" -> "receptionist";
            case "trainer", "recruiter", "instructor", "recrutor" -> "trainer";
            case "secretary", "secretara" -> "secretary";
            case "armorer", "armourer", "armurier", "armuriera" -> "armorer";
            default -> null;
        };
    }

    public static class Checkpoint {
        public String id;
        public String name;
        public String dimension = "minecraft:overworld";
        public Double x;
        public Double y;
        public Double z;

        public boolean isPlaced() {
            return x != null && y != null && z != null;
        }
    }

    public static class Location {
        public String dimension = "minecraft:overworld";
        public double x;
        public double y;
        public double z;
    }

    public Location location(String key) {
        Location direct = locations.get(key);
        if (direct != null) return direct;
        // Worlds created before the Instructor/Recrutor merge persisted the
        // physical desk as "recruiter". Read it through the new canonical key
        // without keeping the legacy alias in the required-location list.
        return "trainer".equals(key) ? locations.get("recruiter") : null;
    }

    public int missionMinutes(String checkpointId, int fallback) {
        Integer configured = missionMinutes.get(checkpointId);
        return configured != null && configured > 0 ? configured : fallback;
    }
}
