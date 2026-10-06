package com.dwurdy.straja.application.service;

import com.dwurdy.straja.support.Fakes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #248 / #245 M3 — the patrol book: deterministic daily rotation through the
 * defect catalogue, edition stamping, and stale-edition semantics. The guide
 * must never leak tier names or roll math to whoever reads it.
 */
class PatrolGuideServiceTest {

    private PatrolGuideService guide() {
        var policies = Fakes.policies();
        policies.artifactSerialPrefix = "RC-";
        return new PatrolGuideService(policies);
    }

    @Test
    void editionsRotateDeterministicallyPerWorldDay() {
        var g = guide();
        assertEquals(g.pages(42), g.pages(42), "same day, same guide");
        assertNotEquals(g.pages(42), g.pages(43), "tomorrow shows other exemplars");
    }

    @Test
    void everyEditionPrintsTheDefectSliceAndAuthenticSpec() {
        var g = guide();
        var pages = g.pages(7);
        assertTrue(pages.get(0).contains("ediția zilei 7"));
        assertTrue(pages.get(1).contains("#RC-"),
                "the authentic format line shows the configured prefix");
        long defectPages = pages.stream().filter(p -> p.contains("Exemplu de fals")).count();
        assertEquals(PatrolGuideService.DEFECTS_PER_EDITION, defectPages);
    }

    @Test
    void editionsGoStaleNeverSilentUpdate() {
        var g = guide();
        assertFalse(g.stale(5, 5));
        assertTrue(g.stale(5, 6));
        assertEquals("ziua-5", g.edition(5));
    }

    @Test
    void rotationWrapsTheCatalogue() {
        // Defect slices wrap modulo the catalogue — day 0 and day 8 (catalogue
        // size) land on the same slice, so the cycle is proven finite.
        assertEquals(PatrolGuideService.rotatedDefects(0),
                PatrolGuideService.rotatedDefects(8));
        assertNotEquals(PatrolGuideService.rotatedDefects(0),
                PatrolGuideService.rotatedDefects(1));
    }

    @Test
    void theGuideNeverLeaksMechanics() {
        var g = guide();
        for (long day = 0; day < 20; day++) {
            for (String page : g.pages(day)) {
                for (String spoiler : new String[]{"N1", "N2", "N3", "N4", "N5",
                        "tier", "Tier", "greut", "probabilit", "șans",
                        "weight", "chance"}) {
                    assertFalse(page.contains(spoiler),
                            "page leaks " + spoiler + ": " + page);
                }
            }
        }
    }
}
