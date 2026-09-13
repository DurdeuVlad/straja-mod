package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PolicyOverrideStore;
import com.dwurdy.straja.domain.model.PolicyRegistry;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyServiceTest {
    private static final class MemStore implements PolicyOverrideStore {
        final Map<String, String> map = new LinkedHashMap<>();
        @Override public Map<String, String> read() { return new LinkedHashMap<>(map); }
        @Override public void write(Map<String, String> overrides) { map.clear(); map.putAll(overrides); }
        @Override public String describe() { return "test-policies.yaml"; }
    }

    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private StrajaPolicies baseline;
    private MemStore store;
    private PolicyService policies;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(0));
        players = new PlayerService(ctx);
        baseline = Fakes.policies();
        store = new MemStore();
        policies = new PolicyService(ctx, players, new AuditService(ctx), baseline, store);
    }

    private TestPlayer commissioner() { return server.add("dwurdy"); }

    @Test
    void setAppliesImmediatelyAndPersists() {
        TestPlayer c = commissioner();
        policies.set(c, "timers.quizCooldownMinutes", "25");
        assertEquals(25, ctx.policies().quizCooldownMinutes, "live policies updated without restart");
        assertEquals("25", store.map.get("timers.quizCooldownMinutes"), "override persisted");
        assertTrue(c.told("aplicat imediat"));
    }

    @Test
    void setValidatesStrictly() {
        TestPlayer c = commissioner();
        policies.set(c, "timers.quizCooldownMinutes", "zece");
        assertEquals(10, ctx.policies().quizCooldownMinutes, "malformed value leaves live value untouched");
        assertFalse(store.map.containsKey("timers.quizCooldownMinutes"));
        assertTrue(c.told("Valoare invalidă"));

        policies.set(c, "fines.allowedAmounts", "10;abc;50");
        assertEquals(List.of(10, 25, 50, 100, 250, 500), ctx.policies().fineAllowedAmounts,
                "partially-malformed structured value rejected wholesale");
    }

    @Test
    void setRejectsUnknownKeys() {
        TestPlayer c = commissioner();
        policies.set(c, "identity.commissionerName", "mallory");
        assertEquals("dwurdy", ctx.policies().commissionerName,
                "identity fields are not runtime-overridable");
        assertTrue(c.told("Cheie necunoscută"));
    }

    @Test
    void resetRestoresBaselineValue() {
        TestPlayer c = commissioner();
        policies.set(c, "timers.quizCooldownMinutes", "25");
        policies.reset(c, "timers.quizCooldownMinutes");
        assertEquals(10, ctx.policies().quizCooldownMinutes, "baseline value restored");
        assertFalse(store.map.containsKey("timers.quizCooldownMinutes"));
        assertTrue(c.told("resetat la valoarea configurată: 10"));
    }

    @Test
    void mapAndQuizKeysRoundTrip() {
        TestPlayer c = commissioner();
        policies.set(c, "salary.perBlock", "1=16;2=24;3=36;4=64");
        assertEquals(16, ctx.policies().salaryPerBlock(1));
        assertEquals(64, ctx.policies().salaryPerBlock(4));

        policies.set(c, "quiz.questions", "juramant|0|Cât timp ai?|30 minute;30");
        assertEquals(1, ctx.policies().quiz.size());
        assertTrue(ctx.policies().quiz.get(0).accepts("30"));
    }

    @Test
    void applyPersistedOverridesMergesOnBoot() {
        store.map.put("timers.foodCooldownMinutes", "5");
        store.map.put("timers.quizCooldownMinutes", "not-a-number");
        List<String> failed = policies.applyPersistedOverrides();
        assertEquals(5, ctx.policies().foodCooldownMinutes, "stored override applied");
        assertEquals(10, ctx.policies().quizCooldownMinutes, "malformed persisted value skipped");
        assertEquals(List.of("timers.quizCooldownMinutes"), failed);
    }

    @Test
    void onlyCommissionerOrOpMayEdit() {
        TestPlayer stranger = server.add("visitor");
        policies.set(stranger, "timers.quizCooldownMinutes", "25");
        assertEquals(10, ctx.policies().quizCooldownMinutes);
        assertTrue(stranger.told("Doar Comisaru'"));
    }

    @Test
    void getShowsOverrideMarker() {
        TestPlayer c = commissioner();
        policies.get(c, "timers.quizCooldownMinutes");
        assertTrue(c.told("(implicit)"));
        policies.set(c, "timers.quizCooldownMinutes", "25");
        policies.get(c, "timers.quizCooldownMinutes");
        assertTrue(c.told("suprascris în test-policies.yaml"));
    }
}
