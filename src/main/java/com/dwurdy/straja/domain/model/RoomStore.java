package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Room registry + waitlist + commissioner selection. */
public class RoomStore {
    public List<Room> rooms = new ArrayList<>();
    public Map<String, Assignment> assignments = new LinkedHashMap<>(); // roomId -> assignment
    public List<WaitlistEntry> waitlist = new ArrayList<>();
    /** In-progress marker selections keyed by player UUID (commissioner tool). */
    public Map<String, MarkerSelection> selections = new LinkedHashMap<>();

    public static class Assignment {
        public String player = "";
        public String playerUuid = "";
        public long assignedAt;
    }

    public static class WaitlistEntry {
        public String player = "";
        public String playerUuid = "";
        public int rank;
        public long queuedAt;
        public int notifiedPosition;
    }

    /** A one-block marker placed by the commissioner while configuring a room/cell. */
    public static class MarkerSelection {
        public String dimension = "minecraft:overworld";
        public int x, y, z;
        public long selectedAt;
    }
}
