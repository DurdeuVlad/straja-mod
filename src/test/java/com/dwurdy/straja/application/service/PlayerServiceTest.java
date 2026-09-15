package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerServiceTest {
    private TestServer server;
    private StrajaPolicies policies;
    private StrajaContext ctx;
    private PlayerService players;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        policies = Fakes.policies();
        ctx = Fakes.context(server, new FixedClock(0), policies);
        players = new PlayerService(ctx);
    }

    @Test
    void nameMatchGrantsCommissionerInLocal() {
        TestPlayer dwurdy = server.add("dwurdy");
        assertTrue(players.isCommissioner(dwurdy));
        assertFalse(players.isCommissioner(server.add("other")));
    }

    @Test
    void readStateIsSideEffectFreeForContextualSurfaces() {
        TestPlayer player = server.add("faq-reader");
        var stored = ctx.players().read(player.uuid());
        stored.lastKnownName = "";
        ctx.players().write(player.uuid(), stored);

        var state = players.readState(player);

        assertEquals("", state.lastKnownName);
        assertEquals("", ctx.players().read(player.uuid()).lastKnownName);
    }

    @Test
    void nameMatchDeniedOutsideLocalWithoutUuidPin() {
        policies.environment = "production";
        policies.commissionerUuid = "";
        TestPlayer dwurdy = server.add("dwurdy");
        // Name alone can never grant commissioner outside local.
        assertFalse(players.isCommissioner(dwurdy));
    }

    @Test
    void onlyPinnedUuidMatchesOutsideLocal() {
        policies.environment = "production";
        UUID pinned = UUID.randomUUID();
        policies.commissionerUuid = pinned.toString();
        // Same name, different account → denied.
        assertFalse(players.isCommissioner("dwurdy", UUID.randomUUID()));
        // Pinned UUID with any name → granted.
        assertTrue(players.isCommissioner("renamed", pinned));
    }

    @Test
    void debugOverrideNeverAppliesOutsideLocal() {
        policies.environment = "production";
        TestPlayer p = server.add("tester");
        var test = ctx.test().read();
        test.debugCommissionerUuid = p.uuid().toString();
        ctx.test().write(test);
        assertFalse(players.isCommissioner(p));
    }

    @Test
    void explicitGateOptOutRestoresNameFallback() {
        policies.environment = "production";
        policies.requireCommissionerUuidOutsideLocal = false;
        policies.requireUuid = false;
        TestPlayer dwurdy = server.add("dwurdy");
        assertTrue(players.isCommissioner(dwurdy));
    }

    @Test
    void identityMatchesUuidBoundRecordsByUuidOnly() {
        TestPlayer p = server.add("alice");
        // UUID-bound record: same name, different uuid → no match.
        assertFalse(PlayerService.identityMatches(p, UUID.randomUUID().toString(), "alice"));
        // UUID-bound record: uuid matches even after a rename.
        assertTrue(PlayerService.identityMatches(p, p.uuid().toString(), "oldname"));
    }

    @Test
    void identityMatchesFallsBackToNameForLegacyRecords() {
        TestPlayer p = server.add("Bob");
        assertTrue(PlayerService.identityMatches(p, "", "bob"));
        assertTrue(PlayerService.identityMatches(p, null, "BOB"));
        assertFalse(PlayerService.identityMatches(p, "", "alice"));
        assertFalse(PlayerService.identityMatches(p, "", ""));
        assertFalse(PlayerService.identityMatches(p, null, null));
    }

    @Test
    void rankPrefixCoversEveryAuthorizedRank() {
        TestPlayer p = server.add("guard");
        for (int rank = 1; rank <= 4; rank++) {
            var st = ctx.players().read(p.uuid());
            st.rank = rank;
            ctx.players().write(p.uuid(), st);
            assertEquals("[" + policies.rankName(rank) + "]", players.rankPrefixFor(p));
        }
    }

    @Test
    void rankPrefixIsNullForCiviliansAndFormerMembers() {
        TestPlayer p = server.add("civ");
        assertNull(players.rankPrefixFor(p));
        var st = ctx.players().read(p.uuid());
        st.rank = 2;
        st.fired = true;
        ctx.players().write(p.uuid(), st);
        assertNull(players.rankPrefixFor(p));
        st.fired = false;
        st.resigned = true;
        ctx.players().write(p.uuid(), st);
        assertNull(players.rankPrefixFor(p));
    }

    @Test
    void rankPrefixSurvivesOffDutyAndSuspendedStates() {
        TestPlayer p = server.add("serg");
        var st = ctx.players().read(p.uuid());
        st.rank = 3;
        ctx.players().write(p.uuid(), st);
        // Off duty → still prefixed.
        assertEquals("[Sergent]", players.rankPrefixFor(p));
        st.suspended = true;
        ctx.players().write(p.uuid(), st);
        assertEquals("[Sergent]", players.rankPrefixFor(p));
    }

    @Test
    void rankPrefixUsesConfiguredDisplayNames() {
        TestPlayer p = server.add("guard");
        var st = ctx.players().read(p.uuid());
        st.rank = 2;
        ctx.players().write(p.uuid(), st);
        assertEquals("[Străjer]", players.rankPrefixFor(p));
        // Renaming a rank updates the prefix — the numeric rank stays 2.
        policies.rankNames.put(2, "Plutonier");
        assertEquals("[Plutonier]", players.rankPrefixFor(p));
        assertEquals(2, ctx.players().read(p.uuid()).rank);
    }

    @Test
    void rankPrefixForCommissionerUsesComisarTitle() {
        TestPlayer dwurdy = server.add("dwurdy");
        assertEquals("[Comisar]", players.rankPrefixFor(dwurdy));
        policies.comisarTitle = "Șef";
        assertEquals("[Șef]", players.rankPrefixFor(dwurdy));
    }
}
