package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.FineStore;
import com.dwurdy.straja.domain.model.FineTask;
import com.dwurdy.straja.domain.model.Incident;
import com.dwurdy.straja.domain.model.IncidentStore;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Predarea voluntară: a wanted player turns themselves in — no officer,
 * no chase, no desk. The institution reads the wanted marks, splits them
 * into debts and violent offences, and resolves accordingly: outstanding
 * fines are settled in one withdrawal, violent marks route through the
 * canonical booking path ({@link PrisonService#arrestNearest}) so cells,
 * waitlist seizure and mark resolution behave exactly like a patrol
 * arrest.
 *
 * <p>Voluntary surrender carries clemency: the sentence is discounted by
 * {@code surrenderDiscountPercent} — turning yourself in is always
 * cheaper than being dragged in. A waitlisted surrenderer stays in
 * institutional cuffs until a bunk frees, mirroring a patrol hold.</p>
 *
 * <p>The offer surface is the tick re-notice: every wanted online player
 * gets the {@code [Predă-te]} link at most once per
 * {@code surrenderOfferSeconds}, so any mark source — kill, bounty,
 * fugitive flag — surfaces the option without touching the mark write
 * paths. Stale clicks are re-gated on wanted state at dispatch.</p>
 */
public class SurrenderService {
    /** The chat token the offer link and the stale-click gate share. */
    public static final String ACTION_ID = "surrender:self";

    private final StrajaContext ctx;
    private final AuditService audit;
    private final WantedService wanted;
    private final CustodyService custody;
    private final PrisonService prison;
    private final FineService fines;
    private final BoloService bolos;
    /** player uuid -> last offer send time (session-scoped re-notice). */
    private final Map<String, Long> lastOffer = new HashMap<>();

    public SurrenderService(StrajaContext ctx, AuditService audit,
                            WantedService wanted, CustodyService custody,
                            PrisonService prison, FineService fines,
                            BoloService bolos) {
        this.ctx = ctx;
        this.audit = audit;
        this.wanted = wanted;
        this.custody = custody;
        this.prison = prison;
        this.fines = fines;
        this.bolos = bolos;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private record Assessment(boolean violent, List<Fine> outstanding) {}

    /** Stale-click gate: the link stays honest only while surrender is offered. */
    public boolean canSurrender(PlayerGateway player) {
        return player != null && player.uuid() != null
                && ctx.policies().surrenderEnabled
                && wanted.isWanted(player.uuid());
    }

    /**
     * Resolves the player's wanted record: pays outstanding fines or
     * books them into custody. Refuses cleanly when not wanted or when
     * already caught — cuffed, escorted or jailed players belong to the
     * custodial flow, not self-service.
     */
    public boolean surrender(PlayerGateway player) {
        if (player == null || player.uuid() == null) return false;
        if (!ctx.policies().surrenderEnabled) {
            player.refuse("straja.surrender.disabled", "straja.remedy.ask_comisar");
            return false;
        }
        if (!wanted.isWanted(player.uuid())) {
            player.refuse("straja.surrender.not_wanted", "straja.remedy.fix_retry");
            return false;
        }
        if (alreadyCaught(player)) {
            player.refuse("straja.surrender.already_caught", "straja.remedy.wait");
            return false;
        }
        var assessment = assess(player);
        boolean ok = assessment.violent() ? prisonPath(player, assessment)
                : debtPath(player);
        if (ok) lastOffer.remove(player.uuid().toString());
        return ok;
    }

    /**
     * The offer surface: wanted online players are re-noticed at most once
     * per cooldown. {@link #canSurrender} alone is not enough — an already
     * cuffed or escorted player is still wanted on paper and must not be
     * invited to a second arrest.
     */
    public void tick() {
        if (!ctx.policies().surrenderEnabled) return;
        long now = now();
        long cooldownMs = Math.max(60, ctx.policies().surrenderOfferSeconds) * 1000L;
        var links = List.of(new PlayerGateway.ActionLink("Predă-te", ACTION_ID, cooldownMs));
        var wantedSet = wanted.wantedUuids(); // one store pass, not per-player parses
        for (var player : ctx.server().onlinePlayers()) {
            if (player == null || player.uuid() == null) continue;
            String key = player.uuid().toString();
            if (!wantedSet.contains(key) || alreadyCaught(player)) continue;
            Long last = lastOffer.get(key);
            if (last != null && now - last < cooldownMs) continue;
            lastOffer.put(key, now);
            player.tellAction("straja.surrender.offer", links);
        }
    }

    /** Cuffed, escorted, downed or already inside — the custodial flow owns them. */
    private boolean alreadyCaught(PlayerGateway player) {
        if (custody.isCuffed(player) || custody.isDowned(player)
                || wanted.isUnderEscort(player)) return true;
        var custodyStore = ctx.custody().read();
        if (custodyStore.bound.containsKey(player.uuid().toString())) return true;
        var rec = ctx.prisonerRegister().read().prisoner(player.uuid().toString());
        return rec != null && (rec.status == PrisonerStatus.IN_CELL
                || rec.status == PrisonerStatus.IN_CAMP
                || rec.status == PrisonerStatus.ESCORTED);
    }

    /**
     * Wanted sources split into debts and violent marks. A BOLO is a debt
     * trace only while a live link reaches an existing fine record — a
     * blank or dangling trace fails closed into violent. Register
     * FUGITIVE and unexpired legacy marks are always violent.
     */
    private Assessment assess(PlayerGateway player) {
        String uuid = player.uuid().toString();
        var outstanding = fines.outstandingFor(player);
        var fineData = ctx.fines().read();
        var incidentData = ctx.incidents().read();
        boolean violent = false;
        for (BoloRecord b : ctx.bolos().read().records) {
            if (b == null || b.status != BoloStatus.ACTIVE || !uuid.equals(b.subjectUuid)) {
                continue;
            }
            if (!debtLinked(b, fineData, incidentData)) {
                violent = true;
                break;
            }
        }
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec != null && rec.status == PrisonerStatus.FUGITIVE) violent = true;
        Long legacy = reg.legacyWantedUntil().get(uuid);
        if (legacy != null && legacy > now()) violent = true;
        return new Assessment(violent, outstanding);
    }

    /**
     * A warrant traces to a debt through any of its recorded links:
     * {@code linkedFineId} directly, {@code linkedArrestTaskId} to the
     * recovery task's fine, or {@code linkedIncidentId} to the incident's
     * fine. Producers write different fields, so all three hops resolve.
     */
    private static boolean debtLinked(BoloRecord bolo, FineStore fineData,
                                      IncidentStore incidentData) {
        if (fine(fineData, bolo.linkedFineId) != null) return true;
        if (!blank(bolo.linkedArrestTaskId) && fineData.tasks != null) {
            for (FineTask task : fineData.tasks) {
                if (task != null && bolo.linkedArrestTaskId.equals(task.id)
                        && fine(fineData, task.fineId) != null) return true;
            }
        }
        if (!blank(bolo.linkedIncidentId) && incidentData.incidents != null) {
            for (Incident incident : incidentData.incidents) {
                if (incident != null && bolo.linkedIncidentId.equals(incident.id)
                        && fine(fineData, incident.linkedFineId) != null) return true;
            }
        }
        return false;
    }

    private static Fine fine(FineStore data, String id) {
        return blank(id) ? null : data.find(id);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Money offences end in one withdrawal: every payable fine marked PAID,
     * recovery tasks completed, reputation rehabilitation fired per fine —
     * then the marks priced by that debt resolve as surrendered. Wanted
     * with no charge outstanding is a stale record: resolved and released.
     */
    private boolean debtPath(PlayerGateway player) {
        var result = fines.settleAllFor(player);
        if (!result.ok()) {
            if ("insufficient_funds".equals(result.reason())) {
                player.refuse("straja.surrender.insufficient", "straja.remedy.reception",
                        result.total());
            } else {
                player.refuse("straja.surrender.failed", "straja.remedy.ask_comisar");
            }
            return false;
        }
        bolos.resolveFor(player.uuid(), BoloStatus.RESOLVED, "surrendered");
        audit.record(AuditActions.SURRENDER_SELF, player.name(), String.valueOf(player.uuid()),
                player.name(), String.valueOf(player.uuid()), AuditResults.SUCCESS,
                "debt_settled=" + result.total() + " fines=" + result.settled());
        player.tell(result.settled() == 0
                ? "Te-ai predat. Cazierul tău este curat — ești liber."
                : "Te-ai predat. Amenzile restante (" + result.total()
                + " monede) au fost achitate și cazierul tău a fost curățat.");
        return true;
    }

    /**
     * Violent offences end in custody: institutional cuffs first so a
     * waitlisted prisoner stays restrained, then the canonical nearest-cell
     * booking. A fugitive with a live sentence is recaptured into it by the
     * booking path itself — the discount only prices new sentences.
     */
    private boolean prisonPath(PlayerGateway player, Assessment assessment) {
        if (!ctx.policies().prisonEnabled) {
            player.refuse("straja.surrender.no_prison", "straja.remedy.ask_comisar");
            return false;
        }
        var cuff = custody.applyCuffsSystem(player, "surrender");
        if (!cuff.ok()) {
            player.refuse("straja.surrender.already_caught", "straja.remedy.wait");
            return false;
        }
        int base = Math.max(1, ctx.policies().prisonDefaultSentenceDays);
        for (Fine fine : assessment.outstanding()) {
            base = Math.max(base, fines.sentenceDaysFor(fine.amount));
        }
        int discount = Math.max(0, Math.min(90, ctx.policies().surrenderDiscountPercent));
        int days = Math.max(1, base * (100 - discount) / 100);
        var sentence = prison.arrestNearest(player, days, "surrender");
        if (sentence == null) {
            player.refuse("straja.surrender.failed", "straja.remedy.retry");
            return false;
        }
        audit.record(AuditActions.SURRENDER_SELF, player.name(), String.valueOf(player.uuid()),
                player.name(), String.valueOf(player.uuid()), AuditResults.SUCCESS,
                "prison_days=" + sentence.sentenceDays + " discount=" + discount);
        player.tell("Te-ai predat. Sentință: " + sentence.sentenceDays
                + " zi(e) — clemență pentru predare voluntară.");
        return true;
    }
}
