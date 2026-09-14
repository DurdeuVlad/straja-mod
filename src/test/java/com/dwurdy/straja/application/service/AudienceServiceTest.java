package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.AudienceUseCase;
import com.dwurdy.straja.domain.model.AudienceRequest;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudienceServiceTest {
    private TestServer server;
    private StrajaPolicies policies;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private AudienceService audiences;
    private TestPlayer member;
    private TestPlayer comisar;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        policies = Fakes.policies();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock, policies);
        players = new PlayerService(ctx);
        audiences = new AudienceService(ctx, players, new AuditService(ctx));
        member = server.add("guard1");
        comisar = server.add("dwurdy");
        var st = ctx.players().read(member.uuid());
        st.rank = 2;
        ctx.players().write(member.uuid(), st);
    }

    @Test
    void requestPersistsPendingAndNotifiesComisar() {
        assertTrue(audiences.request(member, "vreau să discut despre patrulare"));
        var store = ctx.audiences().read();
        var r = store.requests.get("A1");
        assertNotNull(r);
        assertEquals(AudienceRequest.PENDING, r.status);
        assertEquals(member.uuid().toString(), r.requesterUuid);
        // Online Comisar was told (coalesced count).
        assertTrue(comisar.messages.stream().anyMatch(t -> t.contains("cereri de audiență")));
    }

    @Test
    void oneOpenRequestPerMemberUpdatesReason() {
        audiences.request(member, "primul motiv");
        assertTrue(audiences.request(member, "al doilea motiv"));
        var store = ctx.audiences().read();
        assertEquals(1, store.requests.size(), "re-request while PENDING updates the same record");
        assertEquals("al doilea motiv", store.requests.get("A1").reason);
    }

    @Test
    void civilianCannotRequest() {
        assertFalse(audiences.request(server.add("civ"), "motiv"));
    }

    @Test
    void resolveRequiresCommissioner() {
        audiences.request(member, "motiv");
        assertFalse(audiences.resolve(member, "A1", "resolve", ""));
        assertTrue(audiences.resolve(comisar, "A1", "resolve", "ne vedem la 18"));
        var r = ctx.audiences().read().requests.get("A1");
        assertEquals(AudienceRequest.RESOLVED, r.status);
        // Requester is online → outcome delivered immediately.
        assertTrue(member.messages.stream().anyMatch(t -> t.contains("rezolvată") && t.contains("ne vedem la 18")));
        assertTrue(ctx.audiences().read().requests.get("A1").outcomeDelivered);
    }

    @Test
    void dismissedOutcomeDeliversOnLogin() {
        audiences.request(member, "motiv");
        assertTrue(audiences.resolve(comisar, "A1", "dismiss", ""));
        member.messages.clear();
        // simulate logout/login recovery path
        audiences.deliverOutcome(member);
        // Already delivered on resolve (online) → no second tell needed; flag stays true.
        assertTrue(ctx.audiences().read().requests.get("A1").outcomeDelivered);
    }

    @Test
    void unknownDecisionRefused() {
        audiences.request(member, "motiv");
        assertFalse(audiences.resolve(comisar, "A1", "maybe", ""));
    }

    @Test
    void availableActionsExposeRequestAndReviewByRole() {
        var memberActions = audiences.availableActions(member);
        assertTrue(memberActions.stream().anyMatch(a -> a.action() == AudienceUseCase.Action.REQUEST));
        assertFalse(memberActions.stream().anyMatch(a -> a.action() == AudienceUseCase.Action.REVIEW));
        audiences.request(member, "motiv");
        var comisarActions = audiences.availableActions(comisar);
        assertTrue(comisarActions.stream().anyMatch(a -> a.action()
                == AudienceUseCase.Action.REVIEW && "A1".equals(a.requestId())));
    }
}
