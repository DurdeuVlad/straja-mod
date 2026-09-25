package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.StationRepository;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.Station;
import com.dwurdy.straja.domain.model.StationStore;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class StationService {
    private final StationRepository repository;
    private final Clock clock;
    private final IdGenerator ids;

    public StationService(StationRepository repository, Clock clock, IdGenerator ids) {
        this.repository = repository;
        this.clock = clock;
        this.ids = ids;
    }

    public synchronized Station get(String id) { return repository.read().stations.get(id); }

    /**
     * Resolve the station message set without leaking persistence details to
     * player-facing adapters. Missing templates use topology-neutral defaults.
     */
    public synchronized Map<String, String> messageTemplates(String stationId) {
        Station station = get(stationId == null || stationId.isBlank() ? "hq" : stationId);
        Map<String, String> values = new LinkedHashMap<>(defaultMessageTemplates());
        if (station != null && station.messageTemplates != null) values.putAll(station.messageTemplates);
        String stationName = station == null || station.displayName == null || station.displayName.isBlank()
                ? (stationId == null || stationId.isBlank() ? "hq" : stationId) : station.displayName;
        String fallback = station == null || station.fallbackStationId == null || station.fallbackStationId.isBlank()
                ? "" : station.fallbackStationId;
        values.put("{station}", stationName);
        values.put("{fallbackStation}", fallback);
        values.putIfAbsent("{receptionist}", "Recepția");
        values.putIfAbsent("{secretariat}", "Secretariatul");
        values.putIfAbsent("{armorer}", "Armuriera");
        values.putIfAbsent("{trainer}", "Instructorul");
        values.putIfAbsent("{commissionerOffice}", "biroul Comisarului");
        values.putIfAbsent("{prison}", "închisoarea");
        return Map.copyOf(values);
    }

    private static Map<String, String> defaultMessageTemplates() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("faq.economy.equipment",
                "Kitul de serviciu se ridică prin {secretariat}, iar schimbul se face la {armorer}, în stația {station}.");
        values.put("faq.locations.ground",
                "{receptionist} și {trainer} sunt puncte distincte ale stației {station}; cere direcția curentă personalului local.");
        values.put("faq.locations.first",
                "{secretariat} și {commissionerOffice} sunt în stația {station}; locația exactă este cea configurată pentru această stație.");
        values.put("faq.locations.second",
                "Camerele Străjerilor și {armorer} sunt în stația {station}; întreabă personalul local pentru traseul curent.");
        values.put("faq.locations.basement",
                "{prison} este în stația {station}; accesul și operațiunile de custodie depind de statut și autorizație.");
        return values;
    }

    public synchronized Station ensureDefault(SetupData setup) {
        StationStore store = repository.read();
        if (store.stations == null) store.stations = new java.util.LinkedHashMap<>();
        Station station = store.stations.get("hq");
        boolean changed = false;
        if (station == null) {
            station = new Station();
            station.stationId = "hq";
            station.displayName = "Headquarters";
            station.headquarters = true;
            changed = true;
        } else if (!station.headquarters) {
            station.headquarters = true;
            changed = true;
        }
        if (setup != null) {
            for (String key : SetupData.LOCATION_KEYS) {
                SetupData.Location location = setup.location(key);
                if (location != null && !station.locationsByRole.containsKey(key)) {
                    station.locationsByRole.put(key, location);
                    changed = true;
                }
            }
        }
        store.stations.put("hq", station);
        if (changed) {
            store.storeRevision++;
            repository.write(store);
        }
        return station;
    }

    public synchronized Station create(String stationId, String displayName) {
        if (stationId == null || stationId.isBlank()) stationId = ids.newId("station");
        StationStore store = repository.read();
        if (store.stations.containsKey(stationId)) return store.stations.get(stationId);
        Station station = new Station();
        station.stationId = stationId;
        station.displayName = displayName == null || displayName.isBlank() ? stationId : displayName;
        store.stations.put(stationId, station);
        store.storeRevision++;
        repository.write(store);
        return station;
    }

    public synchronized void remove(String stationId) {
        if (stationId == null || stationId.isBlank() || "hq".equals(stationId))
            throw new IllegalArgumentException("hq cannot be removed");
        StationStore store = repository.read();
        if (store.stations.remove(stationId) == null) throw new IllegalArgumentException("unknown station");
        for (Station station : store.stations.values()) {
            if (station != null && stationId.equals(station.fallbackStationId)) station.fallbackStationId = "";
        }
        store.storeRevision++; repository.write(store);
    }

    public synchronized void setJurisdictions(String stationId, java.util.List<String> jurisdictions) {
        StationStore store = repository.read(); Station station = require(store, stationId);
        station.jurisdictions = jurisdictions == null ? new java.util.ArrayList<>()
                : jurisdictions.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
        store.storeRevision++; repository.write(store);
    }

    public synchronized void setLocation(String stationId, String role, SetupData.Location location) {
        if (role == null || role.isBlank() || location == null) throw new IllegalArgumentException("location required");
        StationStore store = repository.read(); Station station = require(store, stationId);
        station.locationsByRole.put(role, location); store.storeRevision++; repository.write(store);
    }

    /** Atomically reserves configured station inventory for one equipment issue. */
    public synchronized void reserveInventory(String stationId, java.util.Map<String, Long> quantities) {
        if (quantities == null || quantities.isEmpty()) return;
        StationStore store = repository.read(); Station station = require(store, stationId);
        if (station.inventoryAccounts == null || station.inventoryAccounts.isEmpty()) return;
        for (var entry : quantities.entrySet()) {
            long available = station.inventoryAccounts.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() == null || entry.getValue() <= 0 || available < entry.getValue())
                throw new IllegalStateException("INSUFFICIENT_STATION_INVENTORY");
        }
        quantities.forEach((item, quantity) -> station.inventoryAccounts.put(item,
                station.inventoryAccounts.get(item) - quantity));
        store.storeRevision++; repository.write(store);
    }

    public synchronized void setMessageTemplate(String stationId, String key, String template) {
        if (key == null || key.isBlank() || template == null || template.isBlank())
            throw new IllegalArgumentException("message template required");
        StationStore store = repository.read(); Station station = require(store, stationId);
        if (station.messageTemplates == null) station.messageTemplates = new java.util.LinkedHashMap<>();
        station.messageTemplates.put(key, template); store.storeRevision++; repository.write(store);
    }

    public synchronized void setFallback(String stationId, String fallbackId) {
        StationStore store = repository.read();
        Station station = require(store, stationId);
        if (fallbackId != null && !fallbackId.isBlank()) require(store, fallbackId);
        String prior = station.fallbackStationId;
        station.fallbackStationId = fallbackId == null ? "" : fallbackId;
        if (!validateFallbacks(store).isEmpty()) {
            station.fallbackStationId = prior;
            throw new IllegalArgumentException("station fallback cycle");
        }
        store.storeRevision++;
        repository.write(store);
    }

    public synchronized Resolution resolve(String requestedStation, String jurisdiction, String capability) {
        StationStore store = repository.read();
        Set<String> visited = new HashSet<>();
        String current = requestedStation == null || requestedStation.isBlank() ? "hq" : requestedStation;
        while (current != null && !current.isBlank() && visited.add(current)) {
            Station station = store.stations.get(current);
            if (station == null || !station.enabled) return new Resolution(null, "DENIED_STATION", visited);
            if (station.serves(jurisdiction)) return new Resolution(station, "LOCAL_OR_FALLBACK", visited);
            current = station.fallbackStationId;
        }
        return new Resolution(null, visited.contains(current) ? "FALLBACK_CYCLE" : "DENIED_JURISDICTION", visited);
    }

    public synchronized java.util.List<String> validateFallbacks() { return validateFallbacks(repository.read()); }

    private static java.util.List<String> validateFallbacks(StationStore store) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (Station station : store.stations.values()) {
            Set<String> seen = new HashSet<>();
            String current = station.stationId;
            while (current != null && !current.isBlank()) {
                if (!seen.add(current)) { errors.add("cycle:" + station.stationId); break; }
                Station next = store.stations.get(current);
                if (next == null) { errors.add("missing:" + current); break; }
                current = next.fallbackStationId;
            }
        }
        return errors;
    }

    private static Station require(StationStore store, String id) {
        Station station = store.stations.get(id);
        if (station == null) throw new IllegalArgumentException("unknown station: " + id);
        return station;
    }

    public record Resolution(Station station, String reason, Set<String> visited) {
        public boolean available() { return station != null; }
    }
}
