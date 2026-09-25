package com.dwurdy.straja.adapter.in.command;

import java.util.Map;

/** Pure command permission manifest; deliberately has no Minecraft runtime dependency. */
final class CommandPermissions {
    static final int ADMIN = 3;
    static final int SETUP = 4;

    private static final Map<String, Integer> ROOT_LEVELS = Map.ofEntries(
            Map.entry("help", ADMIN),
            Map.entry("backup", ADMIN),
            Map.entry("personnel", ADMIN),
            Map.entry("promotion", ADMIN),
            Map.entry("document", ADMIN),
            Map.entry("equipment", ADMIN),
            Map.entry("mobilization", ADMIN),
            Map.entry("campaign", ADMIN),
            Map.entry("settlement", ADMIN),
            Map.entry("outbox", ADMIN),
            Map.entry("doctor", ADMIN),
            Map.entry("station", ADMIN),
            Map.entry("invite", ADMIN),
            Map.entry("recruit", ADMIN),
            Map.entry("recrute", ADMIN),
            Map.entry("quiz", ADMIN),
            Map.entry("start", ADMIN),
            Map.entry("checkpoint", ADMIN),
            Map.entry("checkpoint add", SETUP),
            Map.entry("checkpoint remove", SETUP),
            Map.entry("stop", 0),
            Map.entry("special", ADMIN),
            Map.entry("resign", ADMIN),
            Map.entry("demisie", ADMIN),
            Map.entry("rejoin", ADMIN),
            Map.entry("salary", ADMIN),
            Map.entry("coins", ADMIN),
            Map.entry("food", ADMIN),
            Map.entry("kit", ADMIN),
            Map.entry("merit", ADMIN),
            Map.entry("promote", ADMIN),
            Map.entry("demote", ADMIN),
            Map.entry("suspend", ADMIN),
            Map.entry("fire", ADMIN),
            Map.entry("reinstate", ADMIN),
            Map.entry("faction", ADMIN),
            Map.entry("specialization", ADMIN),
            Map.entry("set-checkpoint", SETUP),
            Map.entry("set-mission-time", SETUP),
            Map.entry("set-location", SETUP),
            Map.entry("setup", SETUP),
            Map.entry("policy", SETUP),
            Map.entry("report", ADMIN),
            Map.entry("message", ADMIN),
            Map.entry("request", ADMIN),
            Map.entry("inbox", ADMIN),
            Map.entry("mission", ADMIN),
            Map.entry("cuffs", ADMIN),
            Map.entry("prison", ADMIN),
            Map.entry("fine", ADMIN),
            Map.entry("complaint", ADMIN),
            Map.entry("room", ADMIN),
            Map.entry("archive", ADMIN),
            Map.entry("identity", ADMIN),
            Map.entry("migrate", SETUP),
            Map.entry("emergency", ADMIN),
            Map.entry("npc", SETUP),
            Map.entry("debug", SETUP),
            Map.entry("test", SETUP),
            Map.entry("status", 0),
            Map.entry("rules", 0),
            Map.entry("regulament", 0),
            Map.entry("npc-action", 0));

    private CommandPermissions() {}

    static int permissionLevel(String command) {
        if (command == null || command.isBlank()) return ADMIN;
        String normalized = command.trim();
        Integer exact = ROOT_LEVELS.get(normalized);
        if (exact != null) return exact;
        String root = normalized.split(" ", 2)[0];
        return ROOT_LEVELS.getOrDefault(root, 0);
    }

    static boolean isAdminOnly(String command) {
        return permissionLevel(command) >= ADMIN;
    }
}
