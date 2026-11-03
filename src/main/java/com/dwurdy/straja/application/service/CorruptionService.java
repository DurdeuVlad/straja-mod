package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Corupție: guards who take bribes are accountable inside the
 * institution, not by admin decree. Every accepted bribe leaves a
 * money-trail record in the append-only audit log; a citizen report
 * ({@code /straja denunt}) checks that trail — evidence, not
 * accusation, is what writes the warrant.
 *
 * <p>A witnessed exchange (a second guard inside the witness radius
 * when the coins move) marks immediately — the institution saw the
 * payment. Otherwise a denunț triggers the trail read: bribe income
 * found → {@link BoloService#flagSystem} corruption warrant → the
 * patrol pipeline arrests the guard like any wanted player. A clean
 * trail files the report as unproven — false accusations cost nothing
 * but time.</p>
 *
 * <p>Audit records carry a {@code kind=} discriminator: only
 * {@code kind=bribe} entries count as money evidence — report and
 * warrant records can never be mistaken for the trail itself.</p>
 */
public class CorruptionService {
    private final StrajaContext ctx;
    private final AuditService audit;
    private final PlayerService players;
    private final BoloService bolos;
    /** reporter uuid -> last denunț time (anti-spam, session-scoped). */
    private final Map<String, Long> lastReport = new HashMap<>();

    public CorruptionService(StrajaContext ctx, AuditService audit,
                             PlayerService players, BoloService bolos) {
        this.ctx = ctx;
        this.audit = audit;
        this.players = players;
        this.bolos = bolos;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    /** One bribe provenance record — the money a guard was caught with. */
    public record BribeEvidence(long at, String prisoner, String prisonerUuid,
                                int amount, boolean witnessed) {}

    /**
     * Called by the escape flow the moment a guard pockets a bribe.
     * The trail lands in the append-only ledger first — a witnessed
     * exchange is conviction-ready and marks on the spot.
     */
    public synchronized void recordBribe(PlayerGateway guard,
                                         PlayerGateway prisoner,
                                         int amount, boolean witnessed) {
        if (guard == null || guard.uuid() == null) return;
        audit.record(AuditActions.CORRUPTION, prisoner == null ? "" : prisoner.name(),
                uuid(prisoner), guard.name(), uuid(guard), AuditResults.SUCCESS,
                "kind=bribe amount=" + amount + " witnessed=" + witnessed);
        if (witnessed && ctx.policies().corruptionEnabled)
            mark(uuid(guard), guard.name(), "witnessed_bribe");
    }

    /**
     * `/straja denunt <nume|uuid>` — a citizen (or a colleague — internal
     * affairs counts) accuses a guard of corruption. The accusation
     * itself convicts nothing; the money trail decides. The suspect
     * resolves online by name/uuid, or offline by uuid and by the name
     * already written on the trail — a corrupt guard can't dodge the
     * report by logging off.
     */
    public synchronized boolean denunt(PlayerGateway reporter, String suspectNameOrUuid) {
        if (reporter == null || reporter.uuid() == null || suspectNameOrUuid == null
                || suspectNameOrUuid.isBlank()) return false;
        if (!ctx.policies().corruptionEnabled) {
            reporter.refuse("straja.denunt.disabled", "straja.remedy.ask_comisar");
            return false;
        }
        var resolved = resolveSuspect(suspectNameOrUuid.trim());
        if (resolved == null) {
            reporter.refuse("straja.denunt.not_staff", "straja.remedy.fix_retry");
            return false;
        }
        if (reporter.uuid().toString().equals(resolved.uuid())) {
            reporter.refuse("straja.denunt.self", "straja.remedy.fix_retry");
            return false;
        }
        long cooldownMs = Math.max(0, ctx.policies().corruptionReportCooldownSeconds) * 1000L;
        Long last = lastReport.get(reporter.uuid().toString());
        if (cooldownMs > 0 && last != null && now() - last < cooldownMs) {
            reporter.refuse("straja.denunt.cooldown", "straja.remedy.wait",
                    (cooldownMs - (now() - last)) / 1000L);
            return false;
        }
        var trail = trailFor(resolved.uuid());
        var state = resolved.online() != null
                ? players.state(resolved.online())
                : players.state(parseUuid(resolved.uuid()));
        boolean staff = resolved.online() != null
                ? players.isOnDutyGuard(resolved.online())
                        || players.isCommissioner(resolved.online()) || state.rank > 0
                : players.isCommissioner(resolved.name(), parseUuid(resolved.uuid()))
                        || state.rank > 0;
        if (trail.isEmpty() && !staff) {
            reporter.refuse("straja.denunt.not_staff", "straja.remedy.fix_retry");
            return false;
        }
        lastReport.put(reporter.uuid().toString(), now());
        if (trail.isEmpty()) {
            audit.record(AuditActions.CORRUPTION, reporter.name(), uuid(reporter),
                    resolved.name(), resolved.uuid(), AuditResults.PENDING,
                    "kind=denunt outcome=unproven");
            reporter.tell("Denunțul a fost verificat — dosarul lui "
                    + resolved.name() + " e curat. Rămâne în evidență.");
            return true;
        }
        int total = trail.stream().mapToInt(BribeEvidence::amount).sum();
        boolean flagged = mark(resolved.uuid(), resolved.name(), "denunt:" + reporter.name());
        audit.record(AuditActions.CORRUPTION, reporter.name(), uuid(reporter),
                resolved.name(), resolved.uuid(), AuditResults.SUCCESS,
                "kind=denunt outcome=" + (flagged ? "warrant" : "trail_found")
                        + " evidence=" + trail.size() + " total=" + total);
        reporter.tell("Denunț confirmat — dosarul arată " + trail.size()
                + " plăți de mită (" + total + " B) pentru " + resolved.name()
                + (flagged ? ". Mandat emis." : ". Dovada rămâne la dosar."));
        return true;
    }

    /** The recorded bribe income of a guard — newest first. */
    public List<BribeEvidence> trailFor(String guardUuid) {
        var out = new ArrayList<BribeEvidence>();
        if (guardUuid == null || guardUuid.isBlank()) return out;
        // Two passes: the legacy corrupt_guard record is written a tick
        // before its kind=bribe twin, so fresh evidence must collect first
        // or the pairing check can never dedupe it.
        for (var entry : ctx.audit().entries()) {
            if (entry == null || !guardUuid.equals(entry.targetUuid)) continue;
            if (isBribeEntry(entry))
                out.add(new BribeEvidence(entry.at, entry.actor, entry.actorUuid,
                        readInt(entry.details, "amount="),
                        entry.details.contains("witnessed=true")));
        }
        for (var entry : ctx.audit().entries()) {
            if (entry == null || !guardUuid.equals(entry.targetUuid)) continue;
            if (isLegacyBribeEntry(entry)
                    && !paired(out, readInt(entry.details, "price="), entry.at))
                out.add(new BribeEvidence(entry.at, entry.actor, entry.actorUuid,
                        readInt(entry.details, "price="), false));
        }
        out.sort((a, b) -> Long.compare(b.at(), a.at()));
        return out;
    }

    private static boolean isBribeEntry(com.dwurdy.straja.domain.model.AuditEntry e) {
        return AuditActions.CORRUPTION.equals(e.action)
                && e.details != null && e.details.startsWith("kind=bribe");
    }

    private static boolean isLegacyBribeEntry(com.dwurdy.straja.domain.model.AuditEntry e) {
        return AuditActions.PRISON_BREAK.equals(e.action)
                && e.details != null && e.details.contains("corrupt_guard");
    }

    /** A legacy record pairs with the kind=bribe twin written the same tick. */
    private static boolean paired(List<BribeEvidence> fresh, int amount, long at) {
        for (var e : fresh)
            if (e.amount() == amount && Math.abs(e.at() - at) < 5000L) return true;
        return false;
    }

    private record Resolved(String uuid, String name, PlayerGateway online) {}

    private Resolved resolveSuspect(String input) {
        PlayerGateway online = null;
        for (var p : ctx.server().onlinePlayers()) {
            if (p != null && p.uuid() != null && (input.equalsIgnoreCase(p.name())
                    || input.equalsIgnoreCase(p.uuid().toString()))) { online = p; break; }
        }
        if (online != null) return new Resolved(online.uuid().toString(), online.name(), online);
        var uuid = parseUuid(input);
        if (uuid != null) return new Resolved(uuid.toString(), input, null);
        // A corrupt guard's name is already in the ledger — resolve through it.
        for (var e : ctx.audit().entries()) {
            if (e == null || e.target == null || e.targetUuid == null) continue;
            if (!input.equalsIgnoreCase(e.target)) continue;
            if (!isBribeEntry(e) && !isLegacyBribeEntry(e)) continue;
            var target = parseUuid(e.targetUuid);
            if (target != null) return new Resolved(e.targetUuid, e.target, null);
        }
        return null;
    }

    private static java.util.UUID parseUuid(String value) {
        try { return java.util.UUID.fromString(value); }
        catch (IllegalArgumentException e) { return null; }
    }

    private boolean mark(String suspectUuid, String suspectName, String basis) {
        var bolo = bolos.flagSystem(suspectUuid, suspectName,
                "corupție — mită prinsă");
        if (bolo == null) return false;
        audit.record(AuditActions.CORRUPTION, "system", "", suspectName,
                suspectUuid, AuditResults.SUCCESS,
                "kind=warrant bolo=" + bolo.id + " basis=" + basis);
        return true;
    }

    private static int readInt(String details, String key) {
        int i = details.indexOf(key);
        if (i < 0) return 0;
        int end = i + key.length();
        int j = end;
        while (j < details.length() && Character.isDigit(details.charAt(j))) j++;
        try {
            return Integer.parseInt(details.substring(end, j));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
