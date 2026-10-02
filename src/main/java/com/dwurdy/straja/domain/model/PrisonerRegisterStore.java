package com.dwurdy.straja.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SavedData aggregate for the prisoner register (prototype
 * {@code strajaPrisonJail}/{@code portCheckpointJail}). Includes the
 * pending-locker bucket: belongings reserved while the prisoner is offline,
 * poured back on next login.
 */
public final class PrisonerRegisterStore {
    public static final int CURRENT_SCHEMA = 1;

    public int schemaVersion = CURRENT_SCHEMA;
    /** detainee uuid -> record. */
    private Map<String, PrisonerRegisterRecord> prisoners;
    /** detainee uuid -> reserved locker point keys awaiting offline release. */
    private Map<String, List<String>> pendingLockers;
    /** released-name -> outstanding fine for names never booked. */
    private Map<String, Integer> legacyFines;
    /** real uuid -> strajaWantedUntil epoch ms (legacy hunt expiry, consumed by M7 guards). */
    private Map<String, Long> legacyWantedUntil;

    public Map<String, PrisonerRegisterRecord> prisoners() {
        if (prisoners == null) prisoners = new LinkedHashMap<>();
        prisoners.values().removeIf(java.util.Objects::isNull);
        return prisoners;
    }

    public Map<String, List<String>> pendingLockers() {
        if (pendingLockers == null) pendingLockers = new LinkedHashMap<>();
        return pendingLockers;
    }

    public Map<String, Integer> legacyFines() {
        if (legacyFines == null) legacyFines = new LinkedHashMap<>();
        return legacyFines;
    }

    public Map<String, Long> legacyWantedUntil() {
        if (legacyWantedUntil == null) legacyWantedUntil = new LinkedHashMap<>();
        return legacyWantedUntil;
    }

    public PrisonerRegisterRecord prisoner(String uuid) {
        return prisoners().get(uuid);
    }

    /** Resolves a record by legacy player name (records imported from KubeJS are name-anchored). */
    public PrisonerRegisterRecord prisonerByName(String name) {
        if (name == null) return null;
        for (var rec : prisoners().values()) {
            if (name.equalsIgnoreCase(rec.detaineeName)) return rec;
        }
        return null;
    }

    public void put(PrisonerRegisterRecord record) {
        prisoners().put(record.detaineeUuid, record);
    }

    public void remove(String uuid) {
        prisoners().remove(uuid);
    }

    /** Re-keys a record under its real UUID once the named player is known online. */
    public void adoptByName(String name, String realUuid) {
        PrisonerRegisterRecord rec = prisonerByName(name);
        if (rec == null || rec.detaineeUuid.equals(realUuid)) return;
        String oldUuid = rec.detaineeUuid;
        prisoners().remove(oldUuid);
        PrisonerRegisterRecord existing = prisoners().get(realUuid);
        if (existing != null) {
            // Merge the thin flag-imported record into the rich name-anchored one.
            rec.arrestCount += existing.arrestCount;
            rec.outstandingFines += existing.outstandingFines;
        }
        rec.detaineeUuid = realUuid;
        prisoners().put(realUuid, rec);
        List<String> pending = pendingLockers().remove(oldUuid);
        if (pending != null) reserveLockers(realUuid, pending);
    }

    /** Reserves locker keys for an offline release (idempotent merge). */
    public void reserveLockers(String uuid, List<String> lockerKeys) {
        List<String> existing = pendingLockers().computeIfAbsent(uuid, k -> new ArrayList<>());
        for (String key : lockerKeys) {
            if (!existing.contains(key)) existing.add(key);
        }
    }

    /** Drains the pending locker list for a player who came online. */
    public List<String> drainPendingLockers(String uuid) {
        List<String> keys = pendingLockers().remove(uuid);
        return keys == null ? List.of() : keys;
    }

    /**
     * Deterministic UUID for a legacy name, matching Mojang's offline-mode
     * convention ({@code OfflinePlayer:<name>} in ISO-8859-1) so migrated
     * records join correctly when the server runs offline mode. Online-mode
     * deployments reconcile via {@link #adoptByName} on first login.
     */
    public static String legacyUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.ISO_8859_1))
                .toString();
    }
}
