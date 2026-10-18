package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.Complaint;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FUN-11: eligibility, docket, hearings, conditions, delegation. */
class ParoleServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private PrisonService prison;
    private ParoleService parole;
    private TestPlayer officer;
    private TestPlayer inmate;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        prison.useSeizure(new SeizureService(ctx, audit));
        prison.useBolos(new BoloService(ctx, players, audit));
        prison.useCamps(new LaborCampService(ctx, players, audit));
        parole = new ParoleService(ctx, players, audit);
        parole.usePrison(prison);

        // "dwurdy" is the commissioner name the test harness recognizes.
        officer = server.add("dwurdy");
        officer.op = true;

        ((TestWorld) ctx.world()).room("minecraft:overworld", -1, 59, -1, 6, 66, 6,
                -1, 61, 2);
        assertTrue(prison.createCell(officer, "celula_1",
                "minecraft:overworld", 0, 60, 0, 5, 65, 5));
        inmate = server.add("inmate");
        inmate.dimension = "minecraft:overworld";
        prison.arrest(inmate, null, 1, officer, null);
        inmate.x = 2; inmate.y = 61; inmate.z = 2;
    }

    private void makeEligible() {
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(inmate.uuid().toString());
        rec.standing = 80;
        ctx.prisonerRegister().write(reg);
        var store = ctx.prison().read();
        var sentence = store.activeSentenceFor(inmate.uuid().toString());
        // Serve 60% of the active time.
        long total = sentence.sentenceDays
                * Math.max(1, ctx.policies().prisonActiveMinutesPerMinecraftDay) * 60_000L;
        sentence.remainingActiveMs = (long) (total * 0.4);
        ctx.prison().write(store);
    }

    private String status(TestPlayer p) {
        var rec = ctx.prisonerRegister().read().prisoner(p.uuid().toString());
        return rec == null || rec.status == null ? "" : rec.status.name();
    }

    private com.dwurdy.straja.domain.model.HearingRecord file() {
        assertTrue(parole.file(inmate, "PAROLE", "am muncit cinstit"));
        return ctx.paroles().read().pendingFor(inmate.uuid().toString());
    }

    // ------------------------------------------------------------ eligibility

    @Test
    void eligibilityBreakdownShowsBlockers() {
        var checks = parole.eligibility(inmate.uuid().toString());
        assertFalse(parole.eligible(inmate.uuid().toString()));
        assertTrue(checks.stream().anyMatch(c -> c.key().equals("standing") && !c.ok()));
        assertTrue(checks.stream().anyMatch(c -> c.key().equals("served") && !c.ok()));
        makeEligible();
        assertTrue(parole.eligible(inmate.uuid().toString()),
                "standing + served time unlock the filing gate");
    }

    @Test
    void unpaidFineBlocksEligibility() {
        makeEligible();
        var fines = ctx.fines().read();
        var fine = new com.dwurdy.straja.domain.model.Fine();
        fine.id = "F-1";
        fine.targetUuid = inmate.uuid().toString();
        fine.status = "ISSUED";
        fines.fines.add(fine);
        ctx.fines().write(fines);
        assertFalse(parole.eligible(inmate.uuid().toString()));
        assertFalse(parole.file(inmate, "PAROLE", ""));
    }

    @Test
    void recentEscapeBlocksEligibility() {
        makeEligible();
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(inmate.uuid().toString());
        rec.lastEscapeAt = clock.nowMillis();
        ctx.prisonerRegister().write(reg);
        assertFalse(parole.eligible(inmate.uuid().toString()));
    }

    // ------------------------------------------------------------ docket

    @Test
    void filingCreatesOrderedDurableDocket() {
        makeEligible();
        var h = file();
        assertNotNull(h);
        assertEquals("FILED", h.status);
        assertFalse(parole.file(inmate, "PAROLE", "again"),
                "one pending case per prisoner");

        // A fresh service instance over the same stores sees the row —
        // a prisoner's place in line survives restarts.
        var parole2 = new ParoleService(ctx, players, audit);
        parole2.usePrison(prison);
        var pending = parole2.pendingDocket();
        assertEquals(1, pending.size());
        assertEquals(h.id, pending.get(0).id);
    }

    @Test
    void openRequiresThePrisonerToAppear() {
        makeEligible();
        var h = file();
        inmate.online = false;
        assertFalse(parole.open(officer, h.id), "an offline prisoner cannot appear");
        inmate.online = true;
        assertTrue(parole.open(officer, h.id));
        assertEquals("HEARING", ctx.paroles().read().hearing(h.id).status);
    }

    @Test
    void decisionDeniedStartsRefileCooldown() {
        makeEligible();
        var h = file();
        assertTrue(parole.open(officer, h.id));
        assertTrue(parole.decide(officer, h.id, "denied"));
        var decided = ctx.paroles().read().hearing(h.id);
        assertEquals("DENIED", decided.status);
        assertTrue(decided.refileAfter > clock.nowMillis());
        assertFalse(parole.file(inmate, "PAROLE", "din nou"),
                "a denied prisoner waits out the cooldown");
        clock.advance(25L * 3_600_000L);
        assertTrue(parole.file(inmate, "PAROLE", "după cooldown"));
    }

    // ------------------------------------------------------------ outcomes

    @Test
    void paroledReleasesIntoConditions() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        assertTrue(parole.decide(officer, h.id, "paroled"));
        assertEquals("PAROLED", status(inmate), "the parolee marker stays visible");
        var condition = ctx.paroles().read().parole(inmate.uuid().toString());
        assertNotNull(condition);
        assertEquals("ACTIVE", condition.status);
        assertNull(ctx.prison().read().activeSentenceFor(inmate.uuid().toString()),
                "the sentence is consumed by the release");
    }

    @Test
    void conditionalSettleBuysRelease() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        assertTrue(parole.decide(officer, h.id, "conditional"));
        assertFalse(parole.settle(inmate), "labor account starts empty");
        assertEquals("IN_CELL", status(inmate));

        var reg = ctx.prisonerRegister().read();
        reg.prisoner(inmate.uuid().toString()).laborAccount =
                ctx.policies().paroleBailAmount + 5;
        ctx.prisonerRegister().write(reg);
        assertTrue(parole.settle(inmate));
        assertEquals("PAROLED", status(inmate));
        assertEquals(5, ctx.prisonerRegister().read()
                .prisoner(inmate.uuid().toString()).laborAccount,
                "bail debits exactly the asked amount");
    }

    @Test
    void conditionalOfferLapsesIntoDenial() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "conditional");
        clock.advance(25L * 3_600_000L);
        parole.tick();
        assertEquals("DENIED", ctx.paroles().read().hearing(h.id).status);
    }

    // ------------------------------------------------------------ conditions

    @Test
    void missedCheckInFlagsTheParoleeWanted() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        parole.checkIn(inmate);
        clock.advance(13L * 3_600_000L);
        parole.tick();
        assertEquals("FUGITIVE", status(inmate),
                "a missed check-in routes through the normal arrest pipeline");
        assertEquals("VIOLATED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status);
    }

    @Test
    void paroleWindowCompletesIntoFreedom() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        parole.checkIn(inmate);
        clock.advance(73L * 3_600_000L);
        parole.tick();
        assertEquals("COMPLETED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status);
        assertEquals("RELEASED", status(inmate));
    }

    @Test
    void rearrestViolatesActiveParole() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        prison.arrest(inmate, null, 1, officer, "fresh crime");
        parole.tick();
        assertEquals("VIOLATED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status);
    }

    @Test
    void offlineParoleeDeadlinesFreeze() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        parole.tick(); // stamps the persisted sweep baseline while online
        var before = ctx.paroles().read().parole(inmate.uuid().toString()).checkInBy;
        inmate.online = false;
        clock.advance(60_000L);
        parole.tick();
        var after = ctx.paroles().read().parole(inmate.uuid().toString());
        assertEquals(before + 60_000L, after.checkInBy,
                "offline time does not burn the check-in clock");
        assertEquals("ACTIVE", after.status);
        inmate.online = true;
        clock.advance(14L * 3_600_000L);
        parole.tick();
        assertEquals("VIOLATED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status,
                "back online, the clock runs again");
        assertEquals("FUGITIVE", status(inmate));
    }

    @Test
    void escapeBetweenFilingAndDecisionBlocksParole() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        var reg = ctx.prisonerRegister().read();
        reg.prisoner(inmate.uuid().toString()).status = PrisonerStatus.FUGITIVE;
        ctx.prisonerRegister().write(reg);
        assertFalse(parole.decide(officer, h.id, "paroled"),
                "a runner cannot be paroled — the case stays for a denial");
        assertEquals("HEARING", ctx.paroles().read().hearing(h.id).status);
    }

    // ------------------------------------------------------------ hearing sides

    @Test
    void victimObjectionBoundsTheDecision() {
        makeEligible();
        var victim = server.add("victim");
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-1";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "UNDER_REVIEW";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        assertTrue(parole.open(officer, h.id));
        assertFalse(parole.decide(officer, h.id, "paroled"),
                "the objection window must run before any decision");
        assertTrue(parole.object(victim, h.id));
        assertEquals(1, ctx.paroles().read().hearing(h.id).objections);
        clock.advance(6L * 60_000L);
        assertTrue(parole.decide(officer, h.id, "paroled"));
        var decided = ctx.paroles().read().hearing(h.id);
        assertEquals("PAROLED", decided.status);
        assertFalse(parole.object(victim, h.id), "a decided case takes no objections");
    }

    @Test
    void advocateConsentIsRecordedWithoutAuthority() {
        makeEligible();
        var h = file();
        var advocate = server.add("avocat");
        assertTrue(parole.advocate(inmate, "avocat"));
        assertEquals("ASKED", ctx.paroles().read().hearing(h.id).advocateStatus);
        assertTrue(parole.advocateAnswer(advocate, true));
        var decided = ctx.paroles().read().hearing(h.id);
        assertEquals("AGREED", decided.advocateStatus);
        assertEquals(advocate.uuid().toString(), decided.advocateUuid);
        // The advocate speaks — they cannot open or decide a case.
        assertFalse(parole.open(advocate, h.id));
    }

    // ------------------------------------------------------------ delegation

    @Test
    void delegationApprovesOnlyCleanCases() {
        makeEligible();
        var h = file();
        assertEquals("CLEAN", parole.matchValue(inmate.uuid().toString()));
        assertTrue(parole.decideDelegated("comisar-uuid", "Comisar", h.id));
        assertEquals("PAROLED", ctx.paroles().read().hearing(h.id).status);
        assertEquals("PAROLED", status(inmate));
    }

    @Test
    void delegationSkipsContestedCases() {
        makeEligible();
        var victim = server.add("victim");
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-9";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "SUBMITTED";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        assertEquals("CONTESTED", parole.matchValue(inmate.uuid().toString()));
        assertFalse(parole.decideDelegated("comisar-uuid", "Comisar", h.id),
                "a victim exists to notify — the case waits for a human");
        assertEquals("FILED", ctx.paroles().read().hearing(h.id).status);
    }
}
