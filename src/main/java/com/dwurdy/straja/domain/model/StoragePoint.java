package com.dwurdy.straja.domain.model;

/** A resolved block position in a dimension. */
public record StoragePoint(String dimension, int x, int y, int z) {
    public StoragePoint {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        else dimension = dimension.trim();
    }

    public String key() {
        return dimension + "|" + x + "," + y + "," + z;
    }

    /** Parses a {@link #key} back into a point; null when malformed. */
    public static StoragePoint fromKey(String key) {
        if (key == null) return null;
        int bar = key.indexOf('|');
        if (bar <= 0 || bar >= key.length() - 1) return null;
        String dimension = key.substring(0, bar);
        String[] parts = key.substring(bar + 1).split(",");
        if (parts.length != 3) return null;
        try {
            return new StoragePoint(dimension, Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
