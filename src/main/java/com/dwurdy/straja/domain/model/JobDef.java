package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FUN-10: a camp job definition parsed from policy config. A job is a
 * recurring quota contract — deliver the listed goods at a work point and
 * the account pays once per cooldown. Distinct inputs, pacing (cooldown),
 * and pay make each job feel different. Format:
 * {@code id|label|item:count+item:count|pay|standing|cooldownSeconds}
 */
public record JobDef(String id, String label, List<Quota> quotas,
                     int pay, int standing, int cooldownSeconds) {

    public record Quota(String itemId, int count) {}

    public static JobDef parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split("\\|");
        if (parts.length < 6) return null;
        List<Quota> quotas = new ArrayList<>();
        for (String q : parts[2].split("\\+")) {
            // item ids carry their own namespace colon — split on the LAST
            // colon so "minecraft:bread:4" yields (minecraft:bread, 4).
            int last = q.trim().lastIndexOf(':');
            if (last <= 0) return null;
            try {
                quotas.add(new Quota(q.trim().substring(0, last),
                        Integer.parseInt(q.trim().substring(last + 1).trim())));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            int pay = Integer.parseInt(parts[3].trim());
            int standing = Integer.parseInt(parts[4].trim());
            int cooldown = Integer.parseInt(parts[5].trim());
            if (pay < 0 || cooldown < 0 || quotas.isEmpty()) return null;
            return new JobDef(parts[0].trim(), parts[1].trim(), quotas, pay,
                    standing, cooldown);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Quota q : quotas) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(q.count()).append("×").append(shortName(q.itemId()));
        }
        return sb.toString();
    }

    private static String shortName(String itemId) {
        int idx = itemId == null ? -1 : itemId.indexOf(':');
        return idx < 0 ? String.valueOf(itemId)
                : itemId.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
