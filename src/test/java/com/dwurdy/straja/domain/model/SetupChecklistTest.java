package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Install-state probes and ordering for the guided /straja setup checklist. */
class SetupChecklistTest {

    private static SetupChecklist.Probes probes() {
        return new SetupChecklist.Probes(new StrajaPolicies(), 1, List.of());
    }

    private static SetupData fullSetup() {
        SetupData setup = SetupData.defaults();
        for (String key : SetupData.LOCATION_KEYS) {
            var location = new SetupData.Location();
            setup.locations.put(key, location);
        }
        for (var point : setup.checkpoints) {
            point.x = 1d; point.y = 64d; point.z = 1d;
        }
        return setup;
    }

    private static NpcRegistry fullRegistry() {
        var registry = new NpcRegistry();
        for (String role : List.of("receptionist", "secretary", "trainer", "armorer")) {
            var record = new NpcRegistry.Record();
            record.entityUuid = "npc-" + role;
            record.role = role;
            registry.npcs.put(record.entityUuid, record);
        }
        return registry;
    }

    // ------------------------------------------------------------ commissioner

    @Test
    void commissionerAppointedRequiresAnIdentity() {
        StrajaPolicies policies = new StrajaPolicies();
        policies.commissionerName = "";
        policies.commissionerUuid = "";
        assertFalse(SetupChecklist.commissionerAppointed(
                new SetupChecklist.Probes(policies, 1, List.of())));

        policies.commissionerName = "dwurdy";
        assertTrue(SetupChecklist.commissionerAppointed(
                new SetupChecklist.Probes(policies, 1, List.of())),
                "a configured name appoints the commissioner");

        policies.commissionerName = "";
        policies.commissionerUuid = "some-uuid";
        assertTrue(SetupChecklist.commissionerAppointed(
                new SetupChecklist.Probes(policies, 1, List.of())),
                "a configured uuid appoints the commissioner");
    }

    // ------------------------------------------------------------ prison cells

    @Test
    void prisonCellsRequireAtLeastOneCell() {
        assertFalse(SetupChecklist.prisonCellsConfigured(
                new SetupChecklist.Probes(new StrajaPolicies(), 0, List.of())));
        assertTrue(SetupChecklist.prisonCellsConfigured(
                new SetupChecklist.Probes(new StrajaPolicies(), 1, List.of())));
    }

    // ------------------------------------------------------------ currency

    @Test
    void currencyRequiresCoinDenominations() {
        StrajaPolicies policies = new StrajaPolicies();
        policies.coinItemIds.clear();
        assertFalse(SetupChecklist.currencyConfigured(
                new SetupChecklist.Probes(policies, 1, List.of())),
                "an empty denomination map means no coin provider");

        policies.coinItemIds.put(1, "mod:coin");
        assertTrue(SetupChecklist.currencyConfigured(
                new SetupChecklist.Probes(policies, 1, List.of())));
    }

    // ------------------------------------------------------------ stations

    @Test
    void stationsFailOnFallbackErrorsOrMissingHq() {
        assertTrue(SetupChecklist.stationProblems(new Station(), List.of()).isEmpty(),
                "an enabled hq with a clean fallback chain is ready");

        assertEquals(List.of("missing:hq"),
                SetupChecklist.stationProblems(null, List.of()));
        Station disabled = new Station();
        disabled.enabled = false;
        assertEquals(List.of("disabled:hq"),
                SetupChecklist.stationProblems(disabled, List.of()));
        assertEquals(List.of("cycle:a", "missing:hq"),
                SetupChecklist.stationProblems(null, List.of("cycle:a")),
                "fallback errors survive alongside hq problems");

        assertFalse(SetupChecklist.stationsReady(
                new SetupChecklist.Probes(new StrajaPolicies(), 1, List.of("missing:hq"))));
        assertTrue(SetupChecklist.stationsReady(
                new SetupChecklist.Probes(new StrajaPolicies(), 1, List.of())));
    }

    // ------------------------------------------------------------ ordering + completion

    @Test
    void nextStepWalksCategoriesInDependencyOrder() {
        SetupData setup = fullSetup();
        NpcRegistry registry = fullRegistry();

        StrajaPolicies noCommissioner = new StrajaPolicies();
        noCommissioner.commissionerName = "";
        noCommissioner.commissionerUuid = "";
        var probes = new SetupChecklist.Probes(noCommissioner, 1, List.of());
        String step = SetupChecklist.nextStep(setup, registry, List.of(), probes);
        assertNotNull(step);
        assertTrue(step.contains("Comisar"), "commissioner comes first — it gates setup here: " + step);
        assertTrue(step.contains("straja-server.toml"), "points at the TOML fix: " + step);

        step = SetupChecklist.nextStep(new SetupData(), registry, List.of(), probes());
        assertTrue(step.contains("setup here"), "locations next: " + step);

        SetupData noCheckpoints = fullSetup();
        for (var point : noCheckpoints.checkpoints) point.x = null;
        step = SetupChecklist.nextStep(noCheckpoints, registry, List.of(), probes());
        assertTrue(step.contains("setup patrol"), "checkpoints next: " + step);

        step = SetupChecklist.nextStep(setup, new NpcRegistry(),
                List.of("receptionist"), probes());
        assertTrue(step.contains("setup npcs"), "npcs next: " + step);

        step = SetupChecklist.nextStep(setup, registry, List.of(),
                new SetupChecklist.Probes(new StrajaPolicies(), 0, List.of()));
        assertTrue(step.contains("prison_marker"), "cells next: " + step);

        StrajaPolicies noCoins = new StrajaPolicies();
        noCoins.coinItemIds.clear();
        step = SetupChecklist.nextStep(setup, registry, List.of(),
                new SetupChecklist.Probes(noCoins, 1, List.of()));
        assertTrue(step.contains("coinItemIds"), "currency next: " + step);

        step = SetupChecklist.nextStep(setup, registry, List.of(),
                new SetupChecklist.Probes(new StrajaPolicies(), 1, List.of("missing:hq")));
        assertTrue(step.contains("station validate"), "stations last: " + step);

        assertNull(SetupChecklist.nextStep(setup, registry, List.of(), probes()));
        assertTrue(SetupChecklist.isComplete(setup, registry, List.of(), probes()));
    }

    @Test
    void isCompleteTracksEveryCategory() {
        SetupData setup = fullSetup();
        NpcRegistry registry = fullRegistry();
        assertFalse(SetupChecklist.isComplete(setup, registry, List.of(),
                new SetupChecklist.Probes(new StrajaPolicies(), 0, List.of())),
                "missing cells block completion");
        assertFalse(SetupChecklist.isComplete(setup, registry, List.of(),
                new SetupChecklist.Probes(new StrajaPolicies(), 1, List.of("cycle:a"))),
                "station errors block completion");
    }
}
