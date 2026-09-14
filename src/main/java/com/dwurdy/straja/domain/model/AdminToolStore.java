package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-holder pending state for the physical admin tools (AT-001): the patrol
 * route being recorded, the survey-rod stamp target, the two cell corners and
 * the captured NPC clone template. Keyed by holder UUID string; an entry is
 * dropped on logout. The store never carries authority — every tool action
 * re-checks the holder in the service.
 */
public class AdminToolStore {
    public Map<String, HolderState> holders = new LinkedHashMap<>();

    public static class HolderState {
        public List<Waypoint> route = new ArrayList<>();
        public Waypoint surveyTarget;
        public Waypoint cellCornerA;
        public Waypoint cellCornerB;
        public CloneTemplate cloneTemplate;
    }

    public static class Waypoint {
        public String dimension = "minecraft:overworld";
        public int x, y, z;

        public boolean samePlace(String otherDimension, int ox, int oy, int oz) {
            return x == ox && y == oy && z == oz
                    && (dimension == null ? otherDimension == null : dimension.equals(otherDimension));
        }
    }

    public static class CloneTemplate {
        public String role;
        public String name;
        public String skin;
    }
}
