package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommissionerBootstrapServiceTest {
    /** In-memory stand-in for the authoritative config pin writer. */
    private static final class MemPinner implements BiFunction<String, String, Boolean> {
        final List<String> calls = new ArrayList<>();
        boolean succeed = true;
        boolean throwOnWrite;
        @Override public Boolean apply(String uuid, String name) {
            calls.add(uuid + "|" + name);
            if (throwOnWrite) throw new IllegalStateException("config not loaded");
            return succeed;
        }
    }

    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private MemPinner pinner;
    private CommissionerBootstrapService bootstrap;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(0));
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        pinner = new MemPinner();
        bootstrap = new CommissionerBootstrapService(ctx, audit, pinner);
    }

    private TestPlayer op() {
        TestPlayer p = server.add("operator");
        p.op = true;
        return p;
    }

    @Test
    void statusReportsUnconfiguredUntilPinned() {
        TestPlayer admin = op();
        bootstrap.status(admin);
        assertTrue(admin.told("nesetat"), "unpinned state is reported honestly");
        assertTrue(admin.told("Mediu:"), "environment + gate flags shown");
    }

    @Test
    void statusIsOpOnly() {
        TestPlayer visitor = server.add("visitor");
        bootstrap.status(visitor);
        assertTrue(visitor.told("operator (OP 4)"), "ordinary players cannot read the surface");
    }

    @Test
    void selectIsOpOnly() {
        TestPlayer visitor = server.add("visitor");
        TestPlayer target = server.add("nominee");
        bootstrap.select(visitor, target);
        assertTrue(pinner.calls.isEmpty(), "non-op selection never reaches the config write");
        assertTrue(visitor.told("operator (OP 4)"));
        assertTrue(ctx.policies().commissionerUuid.isEmpty(), "live identity stays empty");
    }

    @Test
    void selectRejectsOfflineTarget() {
        TestPlayer admin = op();
        bootstrap.select(admin, null);
        assertTrue(pinner.calls.isEmpty(), "no target means no write");
        assertTrue(admin.told("trebuie să fie online"));
    }

    @Test
    void selectPinsLivePoliciesAndPersists() {
        TestPlayer admin = op();
        TestPlayer target = server.add("nominee");
        bootstrap.select(admin, target);

        String uuid = target.uuid().toString();
        assertEquals(List.of(uuid + "|nominee"), pinner.calls, "uuid+name written to config");
        assertEquals(uuid, ctx.policies().commissionerUuid, "live policies updated without restart");
        assertEquals("nominee", ctx.policies().commissionerName);
        assertTrue(admin.told("Comisar setat: nominee"));
        assertTrue(admin.told("persistent"));

        var entry = audit.tail(1).get(0);
        assertEquals(AuditActions.COMMISSIONER_PIN, entry.action);
        assertEquals("operator", entry.actor);
        assertEquals(admin.uuid().toString(), entry.actorUuid);
        assertEquals("nominee", entry.target);
        assertEquals(uuid, entry.targetUuid);
        assertEquals(AuditResults.SUCCESS, entry.result);
    }

    @Test
    void selectGrantsAuthorityOnlyToPinnedIdentity() {
        TestPlayer admin = op();
        TestPlayer target = server.add("nominee");
        StrajaPolicies p = ctx.policies();
        p.environment = "staging";
        p.requireCommissionerUuidOutsideLocal = true;
        assertFalse(players.isConfiguredCommissioner(target), "unpinned player has no authority in staging");
        bootstrap.select(admin, target);
        assertTrue(players.isConfiguredCommissioner(target), "pin takes effect immediately");
        assertFalse(players.isConfiguredCommissioner(server.add("impostor")), "other players stay denied");
    }

    @Test
    void selectReportsPersistFailureWithoutRevokingLivePin() {
        pinner.succeed = false;
        TestPlayer admin = op();
        TestPlayer target = server.add("nominee");
        bootstrap.select(admin, target);

        assertEquals(target.uuid().toString(), ctx.policies().commissionerUuid,
                "live pin still applies so the world stays usable this session");
        assertTrue(admin.told("ATENȚIE"), "operator is told the write failed");
        assertEquals(AuditResults.PERSIST_FAILED, audit.tail(1).get(0).result);
    }

    @Test
    void selectSurvivesPinWriterThrow() {
        pinner.throwOnWrite = true;
        TestPlayer admin = op();
        TestPlayer target = server.add("nominee");
        assertDoesNotThrow(() -> bootstrap.select(admin, target));
        assertTrue(admin.told("ATENȚIE"), "config exception degrades to a reported failure");
        assertEquals(AuditResults.PERSIST_FAILED, audit.tail(1).get(0).result);
    }

    @Test
    void statusAfterPinShowsReadback() {
        TestPlayer admin = op();
        TestPlayer target = server.add("nominee");
        bootstrap.select(admin, target);
        bootstrap.status(admin);
        assertTrue(admin.told("Comisar: nominee"), "readback shows the pinned name");
        assertTrue(admin.told(target.uuid().toString()), "readback shows the pinned uuid");
    }

    @Test
    void unconfiguredStagingStaysFailClosed() {
        StrajaPolicies p = ctx.policies();
        p.environment = "staging";
        p.requireCommissionerUuidOutsideLocal = true;
        p.allowNameFallback = false;
        p.commissionerUuid = "";
        p.commissionerName = "nominee"; // name alone must not grant anything
        TestPlayer target = server.add("nominee");
        assertFalse(players.isConfiguredCommissioner(target),
                "uuid-free staging config grants no authority even when the name matches");
    }
}
