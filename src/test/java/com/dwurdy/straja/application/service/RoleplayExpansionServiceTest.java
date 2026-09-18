package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.BoloAuthority;
import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.CustodyStatus;
import com.dwurdy.straja.domain.model.FineTask;
import com.dwurdy.straja.domain.model.IncidentResolution;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.PlayerCondition;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.RestraintMode;
import com.dwurdy.straja.domain.model.RestraintStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic coverage for the RP expansion's high-risk state transitions. */
class RoleplayExpansionServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private IncidentService incidents;
    private BoloService bolos;
    private EvidenceService evidence;
    private ReputationService reputation;
    private CustodyService custody;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock, Fakes.policies());
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        incidents = new IncidentService(ctx, players, audit);
        bolos = new BoloService(ctx, players, audit);
        evidence = new EvidenceService(ctx, players, audit);
        reputation = new ReputationService(ctx, players, audit, incidents, bolos);
        custody = new CustodyService(ctx, players, audit);
    }

    private TestPlayer addDuty(String name, Rank rank) {
        TestPlayer player = server.add(name);
        var state = players.state(player);
        state.rank = rank.level();
        state.duty = true;
        state.invited = true;
        players.save(player.uuid(), state);
        return player;
    }

    private void restrain(TestPlayer target) {
        var store = ctx.custody().read();
        var state = new CustodyState();
        state.playerId = target.uuid().toString();
        state.playerUuid = target.uuid().toString();
        state.playerName = target.name();
        state.condition = PlayerCondition.CONSCIOUS_RESTRAINED;
        state.custody = CustodyStatus.ARRESTED;
        state.restraint = RestraintStatus.CUFFED;
        state.jailDeliveryDeadlineAt = clock.nowMillis() + 60_000;
        store.states.put(state.playerUuid, state);
        ctx.custody().write(store);
    }

    @Test
    void incidentLifecycleEnforcesOneLeadAndSupportLimit() {
        ctx.policies().incidentMaxSupportingGuards = 1;
        TestPlayer citizen = server.add("citizen");
        TestPlayer lead = addDuty("lead", Rank.GUARD);
        TestPlayer support = addDuty("support", Rank.GUARD);
        TestPlayer overflow = addDuty("overflow", Rank.GUARD);

        var incident = incidents.createCitizenReport(citizen, "furt", "A fost văzut un intrus.");
        assertNotNull(incident);
        assertTrue(incidents.accept(lead, incident.id));
        assertFalse(incidents.accept(support, incident.id));
        assertTrue(incidents.join(support, incident.id));
        assertFalse(incidents.join(overflow, incident.id));
        assertTrue(incidents.resolve(lead, incident.id,
                IncidentResolution.RESOLVED_NO_ACTION.name(), "Zona verificată."));
        assertTrue(incidents.active().isEmpty());
    }

    @Test
    void boloIsInformationalUntilAnAuthoritativeTaskExists() {
        TestPlayer issuer = addDuty("sergeant", Rank.SERGENT);
        TestPlayer subject = server.add("subject");

        var info = bolos.create(issuer, subject, "Caută pentru declarații", "",
                BoloAuthority.INFORMATION_ONLY, "");
        assertNotNull(info);
        assertFalse(bolos.hasArrestAuthority(info));
        assertNull(bolos.create(issuer, subject, "Fără mandat", "",
                BoloAuthority.ARREST_AUTHORIZED, ""));

        var fines = ctx.fines().read();
        var task = new FineTask();
        task.id = "FM-AUTH-1";
        task.kind = "FINE_RECOVERY";
        task.targetUuid = subject.uuid().toString();
        task.status = "OPEN";
        fines.tasks.add(task);
        ctx.fines().write(fines);

        var authorized = bolos.create(issuer, subject, "Execută task-ul", "",
                BoloAuthority.ARREST_AUTHORIZED, "");
        assertNotNull(authorized);
        assertTrue(bolos.hasArrestAuthority(authorized));
    }

    @Test
    void restraintModeTogglesServerSideAndStartsInEscort() {
        TestPlayer guard = addDuty("guard", Rank.GUARD);
        TestPlayer target = server.add("target");
        guard.give(ItemSpec.of(CustodyService.CUFFS, 1));
        assertTrue(custody.applyCuffsDirect(guard, target, "test").ok());
        assertEquals(RestraintMode.ESCORT,
                ctx.custody().read().states.get(target.uuid().toString()).restraintMode);
        assertTrue(custody.toggleRestraintMode(guard, target));
        assertEquals(RestraintMode.HARD,
                ctx.custody().read().states.get(target.uuid().toString()).restraintMode);
    }

    @Test
    void searchRejectsStaleSnapshotAndRecordsExactStack() {
        TestPlayer guard = addDuty("guard", Rank.GUARD);
        TestPlayer target = server.add("target");
        restrain(target);
        target.inventory.slots.set(0, new com.dwurdy.straja.application.port.out.ItemView(
                "minecraft:emerald", 3, 64, Map.of("custom", "signed")));

        var first = evidence.beginSearch(guard, target);
        assertNotNull(first);
        target.inventory.slots.set(0, new com.dwurdy.straja.application.port.out.ItemView(
                "minecraft:emerald", 3, 64, Map.of("custom", "altered")));
        assertFalse(evidence.confiscate(guard, first.token(), 0, 3, "Probă", ""));
        assertEquals(3, target.inventory.stackAt(0).count());

        var second = evidence.beginSearch(guard, target);
        assertNotNull(second);
        assertTrue(evidence.confiscate(guard, second.token(), 0, 3, "Probă", ""));
        var record = evidence.find("E-00001");
        assertNotNull(record);
        assertEquals("minecraft:emerald", record.itemId);
        assertEquals(Map.of("custom", "altered"), record.itemData);
        assertNotEquals("minecraft:emerald", target.inventory.stackAt(0).id());
    }

    @Test
    void confiscationPersistsReferenceDeliveryWhenAdapterFails() {
        TestPlayer guard = addDuty("delivery-guard", Rank.GUARD);
        TestPlayer target = server.add("delivery-target");
        restrain(target);
        target.inventory.slots.set(0, new com.dwurdy.straja.application.port.out.ItemView(
                "minecraft:emerald", 1, 64, Map.of("custom", "signed")));

        var search = evidence.beginSearch(guard, target);
        assertNotNull(search);
        guard.failVerifiedCalls = 1;
        assertTrue(evidence.confiscate(guard, search.token(), 0, 1, "Probă", ""));

        var data = ctx.evidence().read();
        assertNotNull(data.records.get("E-00001"));
        assertNotNull(data.pendingDeliveries.get("E-00001"));
        assertFalse(data.pendingDeliveries.get("E-00001").bagDelivered);
        assertTrue(data.pendingDeliveries.get("E-00001").receiptDelivered);

        evidence.deliverPending(guard);
        assertTrue(guard.inventory.slots.stream()
                .anyMatch(item -> "straja:evidence_bag".equals(item.id())));
        assertFalse(ctx.evidence().read().pendingDeliveries.containsKey("E-00001"));
    }

    @Test
    void confiscationRefusesBeforeExtractionWhenReceiptCannotFit() {
        TestPlayer guard = addDuty("full-target-guard", Rank.GUARD);
        TestPlayer target = new TestPlayer("full-target", 1);
        target.server = server;
        server.players.put(target.uuid, target);
        restrain(target);
        target.inventory.slots.set(0, new com.dwurdy.straja.application.port.out.ItemView(
                "minecraft:emerald", 1, 64, Map.of()));

        var search = evidence.beginSearch(guard, target);
        assertNotNull(search);
        assertFalse(evidence.confiscate(guard, search.token(), 0, 1, "Probă", ""));
        assertEquals("minecraft:emerald", target.inventory.stackAt(0).id());
        assertTrue(ctx.evidence().read().records.isEmpty());
        assertTrue(guard.told("nu are loc pentru dovada de confiscare"));
    }

    @Test
    void boloCancellationFailsClosedForActorWithoutGuardState() {
        TestPlayer issuer = addDuty("bolo-issuer", Rank.SERGENT);
        TestPlayer subject = server.add("bolo-subject");
        var bolo = bolos.create(issuer, subject, "Verificare", "",
                BoloAuthority.INFORMATION_ONLY, "");
        assertNotNull(bolo);

        TestPlayer unknown = server.add("uninitialized-actor");
        assertDoesNotThrow(() -> assertFalse(bolos.cancel(unknown, bolo.id)));
        assertEquals(com.dwurdy.straja.domain.model.BoloStatus.ACTIVE,
                bolos.active().get(0).status);
    }

    @Test
    void reputationIsIdempotentAndDistinguishesRestrainedAndLawfulDeaths() {
        TestPlayer killer = server.add("killer");
        TestPlayer victim = server.add("victim");
        reputation.recordFinalDeath(killer, victim);
        reputation.recordFinalDeath(killer, victim);
        assertEquals(-120, reputation.state(killer).score);

        TestPlayer restrainedVictim = server.add("restrained");
        restrain(restrainedVictim);
        reputation.recordFinalDeath(killer, restrainedVictim);
        assertEquals(-370, reputation.state(killer).score);
        // Duplicate death delivery must not change classification after
        // custody recovery has already removed the restraint state.
        ctx.custody().write(new com.dwurdy.straja.domain.model.CustodyStore());
        reputation.recordFinalDeath(killer, restrainedVictim);
        assertEquals(-370, reputation.state(killer).score);

        TestPlayer guard = addDuty("lawful-guard", Rank.GUARD);
        TestPlayer hostile = server.add("hostile");
        var fines = ctx.fines().read();
        var task = new FineTask();
        task.id = "FM-LAWFUL-1";
        task.kind = "FINE_RECOVERY";
        task.targetUuid = hostile.uuid().toString();
        task.status = "OPEN";
        fines.tasks.add(task);
        ctx.fines().write(fines);
        reputation.recordHostileDamage(guard, hostile);
        reputation.recordFinalDeath(guard, hostile);
        assertEquals(0, reputation.state(guard).score);
    }

    @Test
    void sentenceTasksAreCappedAndRecruitmentUsesReputationGate() {
        TestPlayer subject = server.add("subject");
        reputation.apply(subject, subject, "TEST", "bad-standing", -120,
                "test", false);
        var guards = new GuardService(ctx, players, audit, new EquipmentService(ctx));
        guards.onRecruitmentEligibility(reputation::recruitmentAllowed);
        guards.applyForStraja(subject);
        assertNotEquals("APPLIED", players.state(subject).applicationState);

        for (int i = 1; i <= 6; i++) {
            assertNotNull(reputation.completePrisonTask(subject, "SENT-1", "TASK-" + i));
        }
        reputation.completePrisonTask(subject, "SENT-1", "TASK-1");
        assertEquals(-95, reputation.state(subject).score);
        assertEquals(25, reputation.state(subject).prisonTaskRehabilitation.get("SENT-1"));
    }

    @Test
    void commissionerReversalIsAuditedAndIdempotent() {
        TestPlayer subject = server.add("subject");
        TestPlayer commissioner = server.add("dwurdy");
        reputation.apply(subject, subject, "VOIDABLE_FINE", "FINE-1", -120,
                "fine refusal", false);
        assertNotNull(reputation.reverseSource(commissioner, "VOIDABLE_FINE", "FINE-1"));
        assertEquals(0, reputation.state(subject).score);
        assertNull(reputation.reverseSource(commissioner, "VOIDABLE_FINE", "FINE-1"));
        assertEquals(2, reputation.history(commissioner, subject).size());
    }
}
