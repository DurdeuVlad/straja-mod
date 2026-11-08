package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.ManhuntRecord;
import com.dwurdy.straja.domain.model.ManhuntStore;
import com.dwurdy.straja.domain.model.PermissionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FUN-7: the hunt after a break. A manhunt is a bounded, living pursuit —
 * hunters get position hints that decay (wider, staler) the longer it runs,
 * runners see that they are still hunted, and the hunt has explicit end
 * states: everyone captured, the window cooled into standing warrants, or
 * an officer called it off.
 */
public final class ManhuntService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private DispatchService dispatch;
    private BoloService bolos;

    public ManhuntService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    public void useDispatch(DispatchService dispatch) { this.dispatch = dispatch; }
    public void useBolos(BoloService bolos) { this.bolos = bolos; }

    private ManhuntStore store() {
        ManhuntStore data = ctx.manhunts().read();
        data.ensure();
        return data;
    }
    private long now() { return ctx.clock().nowMillis(); }

    /**
     * Opens a manhunt. {@code prisoners} are sentenced runners (recapture adds
     * time); other participants are the springers. Raises an URGENT ALL-band
     * dispatch call.
     */
    public synchronized ManhuntRecord begin(List<ManhuntRecord.Runner> runners,
                                            String dimension, double x, double y, double z,
                                            String originLabel, String sourceId) {
        if (runners == null || runners.isEmpty()) return null;
        ManhuntStore data = store();
        ManhuntRecord hunt = new ManhuntRecord();
        hunt.id = data.nextHuntId();
        for (ManhuntRecord.Runner r : runners) {
            // Already-hunted runners fold into the existing hunt's hunt record
            // (their runner entry stays there); they can still appear here.
            var copy = new ManhuntRecord.Runner();
            copy.uuid = r.uuid; copy.name = r.name; copy.wasPrisoner = r.wasPrisoner;
            hunt.runners.add(copy);
        }
        hunt.dimension = dimension == null || dimension.isBlank()
                ? "minecraft:overworld" : dimension;
        hunt.originLabel = originLabel == null ? "" : originLabel;
        hunt.startedAt = now();
        hunt.lastHintAt = now();
        hunt.coolAt = now() + Math.max(1, ctx.policies().manhuntMinutes) * 60_000L;
        data.hunts.add(hunt);
        ctx.manhunts().write(data);
        audit.record(AuditActions.INCIDENT_CREATE, "manhunt", "", hunt.id, sourceId,
                AuditResults.SUCCESS, "runners=" + hunt.runners.size());
        if (dispatch != null) {
            // sourceId = the hunt's own id — two hunts on the same cell must
            // never share a call, or closing one closes the other.
            var breakerUuid = hunt.runners.isEmpty() ? "" : hunt.runners.get(0).uuid;
            var call = dispatch.raise(DispatchService.SOURCE_MANHUNT, hunt.id,
                    "Vânătoare: " + hunt.runners.size() + " fugitiv" + (hunt.runners.size() == 1 ? "" : "i"),
                    originLabel, hunt.dimension, x, y, z, originLabel,
                    "", "", breakerUuid, "URGENT", "ALL");
            if (call != null) {
                hunt.dispatchCallId = call.id;
                ctx.manhunts().write(data);
            }
        }
        for (ManhuntRecord.Runner r : hunt.runners) {
            PlayerGateway p = onlineByUuid(r.uuid);
            if (p != null) p.tell("§cEști vânat! Vânătoarea " + hunt.id
                    + " începe — fugi sau predă-te. Timp până la ordin de reținere: "
                    + Math.max(1, ctx.policies().manhuntMinutes) + " min.");
        }
        return hunt;
    }

    /** Periodic sweep (20-tick): decaying hints, cool-off, closure. */
    public synchronized void tick() {
        ManhuntStore data = store();
        boolean dirty = false;
        for (ManhuntRecord hunt : data.hunts) {
            if (hunt == null || !"ACTIVE".equals(hunt.status)) continue;
            long now = now();
            if (hunt.runners.isEmpty()) {
                hunt.status = "CAPTURED";
                hunt.endedReason = "all_runners_captured";
                dirty = true;
                closeCall(hunt, "captured");
                audit.record(AuditActions.PRISON_RECAPTURE, "manhunt", "", hunt.id, "",
                        AuditResults.SUCCESS, hunt.endedReason);
                continue;
            }
            if (now >= hunt.coolAt) {
                // The chase cooled — every remaining runner converts to a
                // standing arrest warrant instead of an active hunt.
                hunt.status = "COOLED";
                hunt.endedReason = "window_elapsed";
                for (ManhuntRecord.Runner r : hunt.runners) {
                    if (bolos != null && r != null && !r.uuid.isBlank())
                        bolos.flagSystem(r.uuid, r.name, "mandat: vânătoare " + hunt.id);
                    PlayerGateway p = onlineByUuid(r.uuid);
                    if (p != null) p.tell("§7Vânătoarea s-a răcit — acum ai un mandat de reținere.");
                }
                closeCall(hunt, "cooled_to_warrants");
                dirty = true;
                continue;
            }
            long hintEvery = Math.max(10, ctx.policies().manhuntHintSeconds) * 1_000L;
            if (now - hunt.lastHintAt >= hintEvery) {
                hunt.lastHintAt = now;
                dirty = true;
                pushHints(hunt);
            }
        }
        int retention = Math.max(10, ctx.policies().manhuntRetentionLimit);
        int terminal = 0;
        for (ManhuntRecord h : data.hunts) {
            if (h != null && !"ACTIVE".equals(h.status)) terminal++;
        }
        int excess = terminal - retention;
        if (excess > 0) {
            var it = data.hunts.iterator();
            while (it.hasNext() && excess > 0) {
                ManhuntRecord h = it.next();
                if (h != null && !"ACTIVE".equals(h.status)) { it.remove(); excess--; }
            }
            dirty = true;
        }
        if (dirty) ctx.manhunts().write(data);
    }

    /** Hints degrade with age — the radius grows, never pinpoints forever. */
    private void pushHints(ManhuntRecord hunt) {
        double ageMinutes = (now() - hunt.startedAt) / 60_000.0;
        int radius = (int) Math.min(500,
                ctx.policies().manhuntHintBaseRadius
                        + ageMinutes * ctx.policies().manhuntHintGrowthPerMinute);
        for (ManhuntRecord.Runner r : hunt.runners) {
            PlayerGateway runner = onlineByUuid(r.uuid);
            if (runner == null) continue;
            if (!hunt.dimension.isBlank() && !hunt.dimension.equals(runner.dimension())) continue;
            double hx = runner.x() + ThreadLocalRandom.current().nextDouble(-radius, radius);
            double hz = runner.z() + ThreadLocalRandom.current().nextDouble(-radius, radius);
            String hint = "§eVânătoare " + hunt.id + ": " + r.name
                    + " semnalat spre x:" + (int) hx + " z:" + (int) hz
                    + " (±" + radius + "m)";
            for (PlayerGateway p : ctx.server().onlinePlayers()) {
                if (p == null || !hunt.dimension.equals(p.dimension())) continue;
                // A runner who is also on duty never receives their own trail.
                if (p.uuid() != null && hunt.runner(p.uuid().toString())) continue;
                if (players.isOnDutyGuard(p)) p.tell(hint);
            }
        }
    }

    /**
     * A runner is back in custody — removes them from the hunt; the hunt
     * closes when no runners remain. Returns the record for sentence math.
     */
    public synchronized ManhuntRecord runnerCaptured(String runnerUuid) {
        ManhuntStore data = store();
        ManhuntRecord hunt = data.activeForRunner(runnerUuid);
        if (hunt == null) return null;
        hunt.runners.removeIf(r -> r != null && r.uuid.equals(runnerUuid));
        ctx.manhunts().write(data);
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p != null && players.isOnDutyGuard(p))
                p.tell("§aVânătoare " + hunt.id + ": un fugitiv a fost prins. "
                        + hunt.runners.size() + " rămași.");
        }
        if (hunt.runners.isEmpty()) tick(); // closes as CAPTURED
        return hunt;
    }

    /** Officer call-off / commissioner pardon mid-hunt. */
    public synchronized boolean callOff(PlayerGateway actor, String huntId, String reason) {
        if (actor == null) return false;
        var level = players.permissionLevel(actor, players.state(actor));
        if (level != PermissionLevel.LIEUTENANT && level != PermissionLevel.COMMISSIONER) {
            actor.refuse("straja.manhunt.calloff_rank", "straja.remedy.inspector");
            return false;
        }
        ManhuntStore data = store();
        ManhuntRecord hunt = data.find(huntId);
        if (hunt == null || !"ACTIVE".equals(hunt.status)) {
            actor.refuse("straja.manhunt.inactive", "straja.remedy.retry");
            return false;
        }
        hunt.status = "CALLED_OFF";
        hunt.endedReason = reason == null ? "" : reason;
        ctx.manhunts().write(data);
        closeCall(hunt, "called_off");
        audit.record(AuditActions.INCIDENT_RESOLVE, actor.name(),
                actor.uuid() == null ? "" : actor.uuid().toString(),
                hunt.id, "", AuditResults.SUCCESS, hunt.endedReason);
        for (ManhuntRecord.Runner r : hunt.runners) {
            PlayerGateway p = onlineByUuid(r.uuid);
            if (p != null) p.tell("§7Vânătoarea " + hunt.id + " a fost oprită.");
        }
        actor.tell("Vânătoarea " + hunt.id + " a fost oprită.");
        return true;
    }

    /** Active hunts a member is a runner in — the pursuit status surface. */
    public synchronized ManhuntRecord huntFor(String uuid) {
        return store().activeForRunner(uuid);
    }

    /** Live hunts for board surfaces — the read-only snapshot a desk page renders. */
    public synchronized List<ManhuntRecord> activeHunts() {
        var out = new ArrayList<ManhuntRecord>();
        for (ManhuntRecord h : store().hunts) {
            if (h != null && "ACTIVE".equals(h.status)) out.add(h);
        }
        return out;
    }

    /** Chat fallback for the jailer's hunt board — the same rows the desk page shows. */
    public synchronized void board(PlayerGateway player) {
        if (player == null) return;
        var hunts = activeHunts();
        if (hunts.isEmpty()) {
            player.tell("Liniște — toate dosarele de vânătoare sunt închise.");
            return;
        }
        for (ManhuntRecord h : hunts) {
            long left = Math.max(0, h.coolAt - now()) / 60_000L;
            player.tell("Vânătoare " + h.id + " — " + h.runners.size()
                    + " fugitiv" + (h.runners.size() == 1 ? "" : "i")
                    + ", ~" + left + " min până la mandat"
                    + (h.originLabel.isBlank() ? "" : " — origine: " + h.originLabel));
        }
    }

    /** Runner-facing pursuit status for the bare {@code /straja manhunt}. */
    public synchronized void status(PlayerGateway player) {
        if (player == null || player.uuid() == null) return;
        var hunt = huntFor(player.uuid().toString());
        if (hunt == null) {
            player.tell("Liniște — fără dosar de vânătoare activ pe numele tău.");
            return;
        }
        long left = Math.max(0, hunt.coolAt - now()) / 60_000L;
        player.tell("§cVânătoare " + hunt.id + " activă — " + hunt.runners.size()
                + " fugitiv" + (hunt.runners.size() == 1 ? "" : "i")
                + ", încă ~" + left + " min până la mandat. "
                + (hunt.originLabel.isBlank() ? "" : "Origine: " + hunt.originLabel + "."));
    }

    private void closeCall(ManhuntRecord hunt, String outcome) {
        if (dispatch != null && hunt != null)
            dispatch.closeForSource(DispatchService.SOURCE_MANHUNT, hunt.id, outcome);
    }

    private PlayerGateway onlineByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p != null && p.uuid() != null && uuid.equalsIgnoreCase(p.uuid().toString()))
                return p;
        }
        return null;
    }
}
