package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.Mission;
import java.util.Map;
import java.util.Set;

/** Generators create offers; they never bypass mission verification or settlement. */
public final class MissionGeneratorService {
    public interface Generator {
        String profession();
        Mission generate(String creatorUuid, String stationId, String jurisdiction, long now, Map<String, String> input);
    }

    private final java.util.Map<String, Generator> generators = new java.util.LinkedHashMap<>();
    public void register(Generator generator) { if (generator != null) generators.put(generator.profession(), generator); }
    public Set<String> professions() { return Set.copyOf(generators.keySet()); }

    /** Registers the contract's initial repeatable work generators. */
    public void registerDefaults() {
        register(defaultGenerator("MINER", "Extrage minereu pentru campania activă."));
        register(defaultGenerator("BLACKSMITH", "Prelucrează materiale pentru echipamentul Străjii."));
        register(defaultGenerator("LOGISTICIAN", "Livrează materiale între stații."));
    }

    public Mission generate(String profession, String creatorUuid, String stationId, String jurisdiction,
                            long now, Map<String, String> input) {
        Generator generator = generators.get(profession);
        if (generator == null) throw new IllegalArgumentException("profession generator unavailable");
        Mission mission = generator.generate(creatorUuid, stationId, jurisdiction, now, input == null ? Map.of() : input);
        if (mission != null) { mission.origin = "GENERATOR:" + profession; mission.status = "ISSUED"; }
        return mission;
    }

    public Mission publishFor(String profession, String beneficiaryUuid, String stationId,
                              String jurisdiction, long now, String offerKey,
                              MissionV2Service missions) {
        Generator generator = generators.get(profession);
        if (generator == null) throw new IllegalArgumentException("profession generator unavailable");
        Mission blueprint = generate(profession, "GENERATOR:" + profession, stationId, jurisdiction, now, Map.of());
        return missions.publishGenerated("GENERATOR:" + profession, beneficiaryUuid, stationId, jurisdiction,
                blueprint.objective, profession, blueprint.dueAt, blueprint.maxAssignees, offerKey);
    }

    private static Generator defaultGenerator(String profession, String objective) {
        return new Generator() {
            @Override public String profession() { return profession; }
            @Override public Mission generate(String creatorUuid, String stationId, String jurisdiction,
                                              long now, Map<String, String> input) {
                Mission mission = new Mission();
                mission.creatorUuid = creatorUuid;
                mission.issuerUuid = creatorUuid;
                mission.stationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
                mission.jurisdiction = jurisdiction == null ? "" : jurisdiction;
                mission.profession = profession;
                mission.missionType = "PROFESSIONAL_WORK";
                mission.objective = objective;
                mission.createdAt = now;
                mission.dueAt = now + 86_400_000L;
                mission.originalDeadline = mission.dueAt;
                mission.maxAssignees = 1;
                return mission;
            }
        };
    }
}
