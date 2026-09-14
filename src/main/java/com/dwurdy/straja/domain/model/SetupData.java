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

    /** Fresh setup with the four reference checkpoint slots, all unplaced. */
    public static SetupData defaults() {
        SetupData data = new SetupData();
        for (int i = 1; i <= 4; i++) {
            var point = new Checkpoint();
            point.id = "checkpoint_" + i;
            point.name = "Checkpoint " + i;
            data.checkpoints.add(point);
        }
        return data;
    }

    public static final String HQ = "hq";

    public static final String[] LOCATION_KEYS = {
            "reportsLectern", "commissionerMailbox", "commissionerOffice",
            "receptionist", "secretary", "prisonRelease", "infirmary", "trainer",
            "recruiter", HQ
    };

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
        return locations.get(key);
    }

    public int missionMinutes(String checkpointId, int fallback) {
        Integer configured = missionMinutes.get(checkpointId);
        return configured != null && configured > 0 ? configured : fallback;
    }
}
