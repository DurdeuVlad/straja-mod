package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action;
import com.dwurdy.straja.application.port.out.PolicyOverrideStore;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminServiceTest {
    private static final class MemStore implements PolicyOverrideStore {
        final Map<String, String> map = new LinkedHashMap<>();
        @Override public Map<String, String> read() { return new LinkedHashMap<>(map); }
        @Override public boolean write(Map<String, String> overrides) { map.clear(); map.putAll(overrides); return true; }
        @Override public String describe() { return "test-policies.yaml"; }
    }

    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private GuardService guards;
    private EmergencyService emergency;
    private PolicyService policy;
    private AdminService admin;
    private TestPlayer comisar;
    private TestPlayer member;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(1_000_000L), Fakes.policies());
        players = new PlayerService(ctx);
        AuditService audit = new AuditService(ctx);
        guards = new GuardService(ctx, players, audit, new EquipmentService(ctx));
        emergency = new EmergencyService(ctx, players, audit);
        policy = new PolicyService(ctx, players, audit, Fakes.policies(), new MemStore());
        admin = new AdminService(ctx, players, guards, policy, emergency);
        comisar = server.add("dwurdy");
        member = server.add("guard1");
        setRank(member, 2);
        players.state(member); // stamps lastKnownName so the roster can name them
    }

    private void setRank(TestPlayer player, int rank) {
        var st = ctx.players().read(player.uuid());
        st.rank = rank;
        ctx.players().write(player.uuid(), st);
    }

    private boolean audited(String action) {
        return ctx.audit().tail(200).stream().anyMatch(e -> action.equals(e.action));
    }

    // ---------------------------------------------------------------- gate

    @Test
    void nonCommissionerGetsNoActionsAndIsRefusedEverywhere() {
        assertTrue(admin.availableActions(member).isEmpty(),
                "a plain member must see no admin actions");

        admin.personnel(member);
        admin.dossier(member, member.uuid().toString());
        admin.promote(member, member.uuid().toString());
        admin.authorize(member, "guard1", 2);
        admin.policyList(member);
        admin.emergencyStatus(member);

        assertTrue(member.told("Doar Comisarul"),
                "every admin entry point must refuse a non-Comisar actor");
        assertFalse(member.told("Personal înregistrat"));
    }

    @Test
    void opActorIsAuthorizedLikeTheCommissioner() {
        TestPlayer op = server.add("op-admin");
        op.op = true;

        assertFalse(admin.availableActions(op).isEmpty());
        admin.personnel(op);
        assertTrue(op.told("Personal înregistrat"), "op actors pass the gate");
    }

    // ---------------------------------------------------------------- roster & dossier

    @Test
    void personnelListsMemberRecordsAndSkipsCivilians() {
        TestPlayer civilian = server.add("civil");
        players.state(civilian); // a bare record with no membership markers

        admin.personnel(comisar);

        assertTrue(comisar.told("Personal înregistrat"));
        assertTrue(comisar.told("guard1"), "member appears by name");
        assertFalse(comisar.told("civil"), "a bare civilian record must not list");
    }

    @Test
    void activeRosterListsOnlyOnDutyMembers() {
        var st = ctx.players().read(member.uuid());
        st.duty = true;
        ctx.players().write(member.uuid(), st);

        admin.activeRoster(comisar);
        assertTrue(comisar.told("În serviciu (1)"));
        assertTrue(comisar.told("guard1"));

        st.duty = false;
        ctx.players().write(member.uuid(), st);
        comisar.messages.clear();
        admin.activeRoster(comisar);
        assertTrue(comisar.told("Nimeni în serviciu"));
    }

    @Test
    void dossierShowsRankLifecycleAndIdentity() {
        admin.dossier(comisar, member.uuid().toString());

        assertTrue(comisar.told("Dosar guard1"));
        assertTrue(comisar.told("Rang:"), "dossier shows the rank line");

        comisar.messages.clear();
        admin.dossier(comisar, "not-a-uuid");
        assertTrue(comisar.told("Membru necunoscut"));
    }

    // ---------------------------------------------------------------- authorize

    @Test
    void authorizeGrantsRankAndAuthorizedApplication() {
        TestPlayer recruit = server.add("recruit1");

        admin.authorize(comisar, "recruit1", 3);

        var st = ctx.players().read(recruit.uuid());
        assertEquals(3, st.rank);
        assertEquals("AUTHORIZED", st.applicationState);
        assertTrue(st.invited);
        assertTrue(recruit.told("autorizat direct"));
        assertTrue(audited("personnel_authorize"), "authorization must be audited");
    }

    @Test
    void authorizeRejectsExistingMemberRecords() {
        admin.authorize(comisar, "guard1", 4);

        assertTrue(comisar.told("are deja o fișă"),
                "existing members go through promote/reinstate, not authorize");
        assertEquals(2, ctx.players().read(member.uuid()).rank);
    }

    // ---------------------------------------------------------------- mutations

    @Test
    void promoteBumpsRankWhenServiceBlocksAreMet() {
        var st = ctx.players().read(member.uuid());
        st.serviceBlocks = 200;
        ctx.players().write(member.uuid(), st);

        admin.promote(comisar, member.uuid().toString());

        assertEquals(3, ctx.players().read(member.uuid()).rank);
        assertTrue(member.told("Promovare"));
        assertTrue(audited("promote"));
    }

    @Test
    void promoteRefusesWhenServiceBlocksAreMissing() {
        admin.promote(comisar, member.uuid().toString());

        assertEquals(2, ctx.players().read(member.uuid()).rank);
        assertTrue(comisar.told("blocuri de serviciu"));
    }

    @Test
    void demoteDropsOneRankAndAudits() {
        setRank(member, 3);
        admin.demote(comisar, member.uuid().toString());

        assertEquals(2, ctx.players().read(member.uuid()).rank);
        assertTrue(member.told("Retrogradare"));
        assertTrue(audited("demote"));
    }

    @Test
    void suspendFlagsAndEndsDutyThenReinstateRestores() {
        var st = ctx.players().read(member.uuid());
        st.duty = true;
        ctx.players().write(member.uuid(), st);

        admin.suspend(comisar, member.uuid().toString());
        st = ctx.players().read(member.uuid());
        assertTrue(st.suspended);
        assertFalse(st.duty, "suspension must end an active shift");
        assertTrue(audited("suspend"));

        assertTrue(admin.isStillValid(comisar, Action.REINSTATE, member.uuid().toString()),
                "a suspended member offers reinstatement");
        admin.reinstate(comisar, member.uuid().toString());
        assertFalse(ctx.players().read(member.uuid()).suspended);
        assertTrue(audited("reinstate"));
    }

    @Test
    void fireClosesTheRecordAndRemovesMemberActions() {
        admin.fire(comisar, member.uuid().toString());

        var st = ctx.players().read(member.uuid());
        assertTrue(st.fired);
        assertEquals(0, st.rank);
        assertTrue(audited("fire"));

        assertFalse(admin.isStillValid(comisar, Action.FIRE, member.uuid().toString()),
                "a fired member no longer offers member actions");
        assertFalse(admin.isStillValid(comisar, Action.PROMOTE, member.uuid().toString()));
    }

    // ---------------------------------------------------------------- stale & forged actions

    @Test
    void staleActionIsRefusedBeforeDispatch() {
        admin.fire(comisar, member.uuid().toString());
        comisar.messages.clear();

        admin.promote(comisar, member.uuid().toString());

        assertTrue(comisar.told("nu mai este disponibilă"),
                "a click minted before the fire must not dispatch");
        assertFalse(ctx.players().read(member.uuid()).rank > 0);
    }

    @Test
    void forgedMemberIdsCannotReachTheMutation() {
        admin.promote(comisar, UUID.randomUUID().toString());
        assertTrue(comisar.told("nu mai este disponibilă"),
                "a valid-format but unknown member id offers no action");

        admin.promote(comisar, "forged; drop table");
        assertTrue(comisar.told("nu mai este disponibilă"),
                "a malformed member id never reaches the repository");
    }

    @Test
    void offlineMemberMutationIsRefused() {
        member.online = false;

        admin.promote(comisar, member.uuid().toString());

        assertTrue(comisar.told("nu este online"),
                "personnel mutations require the member's presence");
        assertEquals(2, ctx.players().read(member.uuid()).rank);
    }

    // ---------------------------------------------------------------- policy & emergency delegation

    @Test
    void policySetThroughAdminSurfaceAppliesImmediately() {
        admin.policySet(comisar, "timers.quizCooldownMinutes", "25");

        assertEquals(25, ctx.policies().quizCooldownMinutes);
        assertTrue(comisar.told("aplicat imediat"));
    }

    @Test
    void emergencyDelegationStartsAndEndsThroughAdminSurface() {
        admin.emergencyStart(comisar, 2.0, 1, "raid");
        assertTrue(ctx.emergency().read().active);

        admin.emergencyEnd(comisar);
        assertFalse(ctx.emergency().read().active);
    }
}
