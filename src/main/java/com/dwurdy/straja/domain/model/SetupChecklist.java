package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure setup-completeness view: which pieces of the Straja installation are
 * still missing. Shared by the guided /straja setup checklist and the
 * commissioner login nudge; no framework types.
 */
public final class SetupChecklist {
    private SetupChecklist() {}

    public static List<String> missingLocations(SetupData setup) {
        var missing = new ArrayList<String>();
        for (String key : SetupData.LOCATION_KEYS) {
            if (setup.locations.get(key) == null) missing.add(key);
        }
        return List.copyOf(missing);
    }

    public static List<String> unplacedCheckpoints(SetupData setup) {
        var missing = new ArrayList<String>();
        for (var point : setup.checkpoints) {
            if (!point.isPlaced()) missing.add(point.id);
        }
        return List.copyOf(missing);
    }

    /**
     * Expected roles not present in the persisted NPC registry.
     * {@code expectedRoles} is supplied by the application layer (the role
     * catalog lives there); order is preserved for display and batch spawn.
     */
    public static List<String> missingNpcRoles(NpcRegistry registry, List<String> expectedRoles) {
        var present = new java.util.HashSet<String>();
        for (var record : registry.npcs.values()) {
            if (record != null && record.role != null) present.add(record.role);
        }
        var missing = new ArrayList<String>();
        for (String role : expectedRoles) {
            if (!present.contains(role)) missing.add(role);
        }
        return List.copyOf(missing);
    }

    /** One-line next-step hint; null when nothing is missing. */
    public static String nextStep(SetupData setup, NpcRegistry registry, List<String> expectedRoles) {
        if (!missingLocations(setup).isEmpty()) {
            return "Lipsesc locațiile administrative — stai unde vrei ghișeele și rulează /straja setup here.";
        }
        if (!unplacedCheckpoints(setup).isEmpty()) {
            return "Lipsesc checkpoint-urile de patrulare — /straja setup patrol creează un traseu pătrat în jurul tău.";
        }
        if (!missingNpcRoles(registry, expectedRoles).isEmpty()) {
            return "Lipsesc NPC-uri — /straja setup npcs spawnează rolurile rămase lângă tine.";
        }
        return null;
    }

    /** True only when every setup category is complete. */
    public static boolean isComplete(SetupData setup, NpcRegistry registry, List<String> expectedRoles) {
        return nextStep(setup, registry, expectedRoles) == null;
    }
}
