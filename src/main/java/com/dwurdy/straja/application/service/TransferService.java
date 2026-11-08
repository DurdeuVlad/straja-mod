package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.LaborCampRecord;
import com.dwurdy.straja.domain.model.ManhuntRecord;
import com.dwurdy.straja.domain.model.MissionBudget;
import com.dwurdy.straja.domain.model.PermissionLevel;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.TransferRecord;
import com.dwurdy.straja.domain.model.TransferStore;

import java.util.ArrayList;
import java.util.List;

/**
 * FUN-8: escorted transfer rides. An ordered camp transfer becomes a ride:
 * the prisoner stays put until an accepting escort takes the chain, is
 * walked through declared risk zones (where distance can part the chain),
 * and the ride has explicit ends — delivered (pays the escorts once),
 * broken (manhunt), suspended back to the board (escort logout), returned
 * to custody, or called off. Nothing teleports the prisoner mid-ride.
 */
public final class TransferService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private DispatchService dispatch;
    private CustodyService custody;
    private PrisonService prison;
    private ManhuntService manhunts;

    public TransferService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    public void useDispatch(DispatchService dispatch) { this.dispatch = dispatch; }
    public void useCustody(CustodyService custody) { this.custody = custody; }
    public void usePrison(PrisonService prison) { this.prison = prison; }
    public void useManhunts(ManhuntService manhunts) { this.manhunts = manhunts; }

    private TransferStore store() {
        TransferStore data = ctx.transfers().read();
        data.ensure();
        return data;
    }
    private long now() { return ctx.clock().nowMillis(); }
    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }

    // ------------------------------------------------------------ ordering

    /**
     * Posts a transfer ride: durable record + URGENT ENLISTED dispatch call.
     * The prisoner keeps their bunk; custody changes only when an escort
     * takes the chain. Idempotent — one live ride per prisoner.
     */
    public synchronized TransferRecord order(PlayerGateway actor, PlayerGateway prisoner,
                                             LaborCampRecord camp, String sentenceId,
                                             String originLabel, String reason) {
        if (prisoner == null || camp == null || !ctx.policies().transferRideEnabled) return null;
        String puuid = uuidOf(prisoner);
        TransferStore data = store();
        var existing = data.activeFor(puuid);
        if (existing != null) return existing;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(puuid);
        if (rec == null || (rec.status != PrisonerStatus.IN_CELL
                && rec.status != PrisonerStatus.IN_CAMP)) return null;
        if (custody != null && custody.isCuffed(prisoner)) return null; // chained elsewhere

        var t = new TransferRecord();
        t.id = data.nextId();
        t.prisonerUuid = puuid;
        t.prisonerName = prisoner.name();
        t.sentenceId = sentenceId == null ? "" : sentenceId;
        t.originLabel = originLabel == null ? "" : originLabel;
        t.originStatus = rec.status.name();
        t.originDimension = prisoner.dimension();
        t.originX = prisoner.x(); t.originY = prisoner.y(); t.originZ = prisoner.z();
        t.destCampId = camp.id;
        t.destLabel = camp.name.isBlank() ? camp.id : camp.name;
        var spawn = camp.intakeSpawn != null ? camp.intakeSpawn : camp.dormitorySpawn;
        if (spawn != null) {
            t.destDimension = camp.dimension;
            t.destX = spawn.x(); t.destY = spawn.y(); t.destZ = spawn.z();
        } else if (camp.boundary == null) {
            return null; // no destination geometry — the ride could never land
        } else {
            t.destDimension = camp.boundary.dimension();
            t.destX = (camp.boundary.minX() + camp.boundary.maxX()) / 2.0;
            t.destY = (camp.boundary.minY() + camp.boundary.maxY()) / 2.0;
            t.destZ = (camp.boundary.minZ() + camp.boundary.maxZ()) / 2.0;
        }
        double dx = t.destX - t.originX, dz = t.destZ - t.originZ;
        t.routeBlocks = Math.sqrt(dx * dx + dz * dz);
        t.status = "ORDERED";
        t.orderedByUuid = actor == null ? "" : uuidOf(actor);
        t.orderedByName = actor == null ? "system" : actor.name();
        t.orderReason = reason == null ? "" : reason;
        t.orderedAt = now();
        t.escortDeadlineAt = now() + waitMs();
        data.transfers.add(t);
        ctx.transfers().write(data);
        raiseCall(t);
        audit.record(AuditActions.CAMP_TRANSFER, t.orderedByName, t.orderedByUuid,
                t.prisonerName, t.prisonerUuid, AuditResults.SUCCESS,
                "ride_ordered " + t.id + " camp=" + t.destCampId + " reason=" + t.orderReason);
        prisoner.tell("Transfer poruncit spre " + t.destLabel
                + " — un escort te ia în lanț. Stai aproape.");
        return t;
    }

    private void raiseCall(TransferRecord t) {
        if (dispatch == null) return;
        var call = dispatch.raise(DispatchService.SOURCE_TRANSFER, t.id,
                "Transfer " + t.id + ": " + t.prisonerName + " → " + t.destLabel,
                t.originLabel, t.originDimension, t.originX, t.originY, t.originZ,
                t.originLabel, t.prisonerUuid, t.prisonerName, t.orderedByUuid,
                "URGENT", "ENLISTED");
        if (call != null) {
            TransferStore data = store();
            var stored = data.find(t.id);
            if (stored != null) {
                stored.dispatchCallId = call.id;
                ctx.transfers().write(data);
            }
        }
    }

    // ------------------------------------------------------------ escorts

    /** Dispatch accept → the accepter signs on as an escort. */
    public synchronized void callAccepted(PlayerGateway player, String callSourceId) {
        joinEscort(player, callSourceId, true);
    }

    /** `/straja transfer join <id>` — a second escort shares the ride. */
    public synchronized boolean join(PlayerGateway player, String transferId) {
        return joinEscort(player, transferId, false);
    }

    private boolean joinEscort(PlayerGateway player, String transferId, boolean viaCall) {
        if (player == null || player.uuid() == null) return false;
        TransferStore data = store();
        var t = data.find(transferId);
        if (t == null || !t.isLive()) {
            player.refuse("straja.transfer.inactive", "straja.remedy.retry");
            return false;
        }
        if (!players.isOnDutyGuard(player)) {
            player.refuse("straja.incident.work_rank", "straja.remedy.duty");
            return false;
        }
        String key = player.uuid().toString();
        if (key.equalsIgnoreCase(t.prisonerUuid)) {
            player.refuse("straja.dispatch.self_subject", "straja.remedy.wait");
            return false;
        }
        if (!t.originDimension.isBlank() && !t.originDimension.equals(player.dimension())) {
            player.refuse("straja.dispatch.dimension", "straja.remedy.travel");
            return false;
        }
        if (t.escort(key) == null) {
            if (t.escorts.size() >= Math.max(1, ctx.policies().transferMaxEscorts)) {
                player.refuse("straja.transfer.escorts_full", "straja.remedy.retry");
                return false;
            }
            var e = new TransferRecord.Escort();
            e.uuid = key; e.name = player.name(); e.acceptedAt = now();
            t.escorts.add(e);
            ctx.transfers().write(data);
            audit.record(AuditActions.INCIDENT_ACCEPT, player.name(), key, t.id, "",
                    AuditResults.SUCCESS, viaCall ? "escort_dispatch" : "escort_join");
        }
        PlayerGateway prisoner = onlineByUuid(t.prisonerUuid);
        player.tell("Ești escort pe " + t.id + " spre " + t.destLabel
                + ". Ia lanțul de la " + t.prisonerName
                + (prisoner == null ? "" : " (x:" + (int) prisoner.x() + " z:" + (int) prisoner.z() + ")")
                + " — încătușează-l. Predare la " + t.destLabel + ".");
        postZones(player);
        return true;
    }

    /**
     * Restraint-applied hook: an escort cuffing their prisoner takes the
     * chain — the ride goes IN_PROGRESS and waypoints post. A returning
     * escort near a still-chained prisoner re-tethers instead.
     */
    public synchronized void onCuffed(PlayerGateway officer, PlayerGateway target) {
        if (officer == null || target == null || target.uuid() == null) return;
        TransferStore data = store();
        var t = data.activeFor(target.uuid().toString());
        if (t == null || !"ORDERED".equals(t.status)) return;
        if (t.escort(uuidOf(officer)) == null) return;
        startRide(data, t, target);
    }

    /**
     * Pickup handoff: an assigned escort within reach of a chained-or-free
     * prisoner takes the chain. `/straja transfer pickup <id>`.
     */
    public synchronized boolean pickup(PlayerGateway player, String transferId) {
        if (player == null || player.uuid() == null) return false;
        TransferStore data = store();
        var t = data.find(transferId);
        if (t == null || !"ORDERED".equals(t.status)) {
            // IN_PROGRESS chains move through the tick's lead-gone handoff —
            // a live ride has nothing to pick up.
            player.refuse("straja.transfer.inactive", "straja.remedy.retry");
            return false;
        }
        if (t.escort(uuidOf(player)) == null) {
            player.refuse("straja.transfer.not_escort", "straja.remedy.dispatch");
            return false;
        }
        PlayerGateway prisoner = onlineByUuid(t.prisonerUuid);
        if (prisoner == null || !prisoner.dimension().equals(player.dimension())) {
            player.refuse("straja.common.target_offline", "straja.remedy.wait");
            return false;
        }
        double dx = player.x() - prisoner.x(), dz = player.z() - prisoner.z();
        double dy = player.y() - prisoner.y();
        double reach = Math.max(6.0, ctx.policies().escortTetherRadius);
        if (dx * dx + dy * dy + dz * dz > reach * reach) {
            player.refuse("straja.transfer.pickup_far", "straja.remedy.travel");
            return false;
        }
        if (custody != null && custody.isCuffed(prisoner)) {
            if (!custody.retether(player, prisoner)) {
                player.refuse("straja.transfer.retether_fail", "straja.remedy.retry");
                return false;
            }
        } else {
            var result = custody == null
                    ? new CustodyService.ApplyResult(false, "no_custody")
                    : custody.applyCuffsDirect(player, prisoner, "transfer:" + t.id);
            if (!result.ok()) {
                player.refuse("straja.transfer.cuff_fail", "straja.remedy.jailer");
                return false;
            }
        }
        // The restraint-applied hook may have started the ride already —
        // the chain holder leads either way.
        if ("ORDERED".equals(t.status)) {
            promoteLead(t, uuidOf(player));
            startRide(data, t, prisoner);
        }
        return true;
    }

    private void startRide(TransferStore data, TransferRecord t, PlayerGateway prisoner) {
        t.status = "IN_PROGRESS";
        t.startedAt = now();
        ctx.transfers().write(data);
        var lead = t.lead();
        for (var e : t.escorts) {
            PlayerGateway esc = onlineByUuid(e.uuid);
            if (esc != null) {
                esc.tell("§6Transfer " + t.id + " pornit — du-l pe " + t.prisonerName
                        + " la " + t.destLabel + " (x:" + (int) t.destX + " z:" + (int) t.destZ + ")."
                        + " Zonele riscante slăbesc lanțul.");
                postZones(esc);
            }
        }
        prisoner.tell("§eEscortul " + (lead == null ? "" : lead.name)
                + " te duce la " + t.destLabel + ". În zonele riscante lanțul cedează.");
        audit.record(AuditActions.PRISON_ESCORT, "transfer", "", t.id, t.prisonerUuid,
                AuditResults.SUCCESS, "ride_start escorts=" + t.escorts.size());
    }

    private void postZones(PlayerGateway escort) {
        var zones = ctx.setup().read().riskZones;
        if (zones == null || zones.isEmpty()) return;
        var names = new StringBuilder();
        for (var zone : zones.values()) {
            if (zone == null) continue;
            if (!names.isEmpty()) names.append(", ");
            names.append(zone.name.isBlank() ? zone.id : zone.name)
                    .append("(x:").append((int) zone.x).append(" z:").append((int) zone.z).append(")");
        }
        if (!names.isEmpty()) escort.tell("§cZone riscante cunoscute: " + names);
    }

    // ------------------------------------------------------------ ride state

    /**
     * Custody gate: a chained transfer prisoner breaks by distance only
     * inside a declared risk zone. Everywhere else the leash holds.
     */
    public synchronized boolean distanceBreakAllowed(String prisonerUuid) {
        var t = store().activeFor(prisonerUuid);
        if (t == null || !t.isLive()) return true;
        if (!"IN_PROGRESS".equals(t.status)) return false;
        PlayerGateway prisoner = onlineByUuid(t.prisonerUuid);
        return prisoner != null && zoneAt(prisoner) != null;
    }

    private SetupData.RiskZone zoneAt(PlayerGateway p) {
        var zones = ctx.setup().read().riskZones;
        if (zones == null) return null;
        for (var zone : zones.values()) {
            if (zone != null && zone.contains(p.dimension(), p.x(), p.y(), p.z())) return zone;
        }
        return null;
    }

    /** Escort death — the chain breaks wherever it happens. */
    public synchronized void escortDied(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        TransferStore data = store();
        for (var t : data.transfers) {
            if (t == null || !"IN_PROGRESS".equals(t.status)) continue;
            var lead = t.lead();
            if (lead == null || !uuid.equalsIgnoreCase(lead.uuid)) continue;
            breakRide(data, t, "escort_killed");
        }
    }

    /** The prisoner died mid-ride — custody recovery owns the body. */
    public synchronized void prisonerDied(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        TransferStore data = store();
        var t = data.activeFor(uuid);
        if (t == null) return;
        t.status = "CANCELLED";
        t.endedReason = "prisoner_died";
        t.endedAt = now();
        ctx.transfers().write(data);
        closeCall(t, "prisoner_died");
        audit.record(AuditActions.PRISON_RECAPTURE, "transfer", "", t.id, t.prisonerUuid,
                AuditResults.SUCCESS, "prisoner_died");
    }

    /** Periodic sweep: pickup handoffs, windows, breaks, delivery, pruning. */
    public synchronized void tick() {
        TransferStore data = store();
        boolean dirty = false;
        long now = now();
        for (var t : data.transfers) {
            if (t == null || !t.isLive()) continue;
            // The sentence ended under the ride (release, parole) — cancel.
            var sentence = ctx.prison().read().activeSentenceFor(t.prisonerUuid);
            if (sentence == null) {
                t.status = "CANCELLED";
                t.endedReason = "sentence_ended";
                t.endedAt = now;
                dirty = true;
                closeCall(t, "sentence_ended");
                continue;
            }
            PlayerGateway prisoner = onlineByUuid(t.prisonerUuid);
            if ("ORDERED".equals(t.status)) {
                if (prisoner == null) continue; // ride waits; never vanishes
                boolean chained = custody != null && custody.isCuffed(prisoner);
                if (chained) {
                    // Any escort of record may take the chain on contact —
                    // a departed lead must not block the handoff.
                    double reach = Math.max(6.0, ctx.policies().escortTetherRadius);
                    for (var e : t.escorts) {
                        if (e == null) continue;
                        var cand = onlineByUuid(e.uuid);
                        if (cand == null || !cand.dimension().equals(prisoner.dimension())) continue;
                        double dx = cand.x() - prisoner.x(), dy = cand.y() - prisoner.y(),
                                dz = cand.z() - prisoner.z();
                        if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
                        if (custody.retether(cand, prisoner)) {
                            promoteLead(t, e.uuid);
                            startRide(data, t, prisoner);
                            break;
                        }
                    }
                    if ("IN_PROGRESS".equals(t.status)) continue;
                }
                if (now >= t.escortDeadlineAt) {
                    returnToCustody(data, t, prisoner);
                    dirty = true;
                }
                continue;
            }
            // IN_PROGRESS
            if (prisoner == null) continue;
            var lead = t.lead();
            PlayerGateway leadPlayer = lead == null ? null : onlineByUuid(lead.uuid);
            if (leadPlayer == null || !leadPlayer.dimension().equals(prisoner.dimension())) {
                // Lead gone — a nearby co-escort takes the chain, else the
                // ride suspends back to the board.
                PlayerGateway takeover = null;
                for (var e : t.escorts) {
                    if (lead != null && e.uuid.equalsIgnoreCase(lead.uuid)) continue;
                    var cand = onlineByUuid(e.uuid);
                    if (cand == null || !cand.dimension().equals(prisoner.dimension())) continue;
                    double dx = cand.x() - prisoner.x(), dy = cand.y() - prisoner.y(),
                            dz = cand.z() - prisoner.z();
                    double reach = Math.max(6.0, ctx.policies().escortTetherRadius) * 3;
                    if (dx * dx + dy * dy + dz * dz <= reach * reach) { takeover = cand; break; }
                }
                if (takeover != null && custody != null && custody.isCuffed(prisoner)
                        && custody.retether(takeover, prisoner)) {
                    t.escorts.removeIf(e -> lead != null && e.uuid.equalsIgnoreCase(lead.uuid));
                    promoteLead(t, uuidOf(takeover));
                    dirty = true;
                    takeover.tell("§6Ai preluat lanțul pentru " + t.id + " — du-l la " + t.destLabel + ".");
                    continue;
                }
                t.status = "ORDERED";
                t.escortDeadlineAt = now + waitMs();
                dirty = true;
                ctx.transfers().write(data);
                raiseCall(t);
                prisoner.tell("§7Escortul a dispărut — un altul preia lanțul. Rămâi pe loc.");
                continue;
            }
            if (custody != null && custody.isDowned(leadPlayer)) {
                breakRide(data, t, "escort_downed");
                dirty = true;
                continue;
            }
            boolean chained = custody == null || custody.isCuffed(prisoner);
            if (!chained) {
                if (insideDestination(t, prisoner)) {
                    settle(data, t, prisoner);
                } else {
                    breakRide(data, t, "chain_broken");
                }
                dirty = true;
                continue;
            }
            if (insideDestination(t, prisoner)) {
                settle(data, t, prisoner);
                dirty = true;
                continue;
            }
            var zone = zoneAt(prisoner);
            String zoneId = zone == null ? "" : zone.id;
            if (!zoneId.equals(t.lastZoneId)) {
                t.lastZoneId = zoneId;
                dirty = true;
                if (zone != null) {
                    String label = zone.name.isBlank() ? zone.id : zone.name;
                    prisoner.tell("§cLanțul e slab — " + label + ". Fugi dacă poți!");
                    for (var e : t.escorts) {
                        var esc = onlineByUuid(e.uuid);
                        if (esc != null) esc.tell("§cZonă riscantă: " + label + " — lanțul poate ceda aici.");
                    }
                } else {
                    prisoner.tell("§7Ai ieșit din zona riscantă — lanțul e solid din nou.");
                }
            }
        }
        if (data.pruneTerminal(ctx.policies().transferRetentionLimit)) dirty = true;
        if (dirty) ctx.transfers().write(data);
    }

    /** Moves the escort to index 0 — the chain holder leads. */
    private static void promoteLead(TransferRecord t, String uuid) {
        var e = t.escort(uuid);
        if (e != null) { t.escorts.remove(e); t.escorts.add(0, e); }
    }

    private boolean insideDestination(TransferRecord t, PlayerGateway prisoner) {
        var camp = ctx.laborCamps().read().camps().get(t.destCampId);
        var b = camp == null ? null : camp.boundary;
        if (b == null) {
            // No wire: reaching within 12 blocks of the posted point counts.
            double dx = prisoner.x() - t.destX, dy = prisoner.y() - t.destY,
                    dz = prisoner.z() - t.destZ;
            return t.destDimension.equals(prisoner.dimension())
                    && dx * dx + dy * dy + dz * dz <= 12 * 12;
        }
        return b.contains(prisoner.dimension(), (int) Math.floor(prisoner.x()),
                (int) Math.floor(prisoner.y()), (int) Math.floor(prisoner.z()));
    }

    /**
     * Delivery settles exactly once: sentence move, restraint off, pay.
     * Custody moves before the DELIVERED write so a crash between them
     * re-settles idempotently — the camp claim and payout receipts dedupe.
     */
    private void settle(TransferStore data, TransferRecord t, PlayerGateway prisoner) {
        if (t.settledAt != 0) return;
        if (prison != null && !prison.completeRideDelivery(t.prisonerUuid, t.destCampId, t.id)) {
            // Destination dissolved mid-ride — send the prisoner home.
            returnToCustody(data, t, prisoner);
            t.endedReason = "destination_gone";
            ctx.transfers().write(data);
            return;
        }
        if (custody != null && custody.isCuffed(prisoner)) custody.releaseChainSilently(prisoner);
        t.settledAt = now();
        t.status = "DELIVERED";
        t.endedAt = t.settledAt;
        t.endedReason = "delivered";
        payEscorts(t);
        ctx.transfers().write(data);
        closeCall(t, "delivered");
        prisoner.tell("§aAi ajuns la " + t.destLabel + " — transfer încheiat.");
        audit.record(AuditActions.CAMP_TRANSFER, "transfer", "", t.id, t.prisonerUuid,
                AuditResults.SUCCESS, "delivered escorts=" + t.escorts.size());
    }

    private void payEscorts(TransferRecord t) {
        double hours = Math.max(1.0 / 60.0,
                t.routeBlocks / Math.max(100.0, ctx.policies().transferWalkBlocksPerHour));
        int amount = MissionBudget.perParticipant(
                ctx.policies().salaryPerHour(Rank.STAGIAR.level()),
                hours, Math.max(0.1, ctx.policies().transferRiskMultiplier));
        for (var e : t.escorts) {
            if (e == null || e.paid) continue;
            PlayerGateway esc = onlineByUuid(e.uuid);
            if (esc == null) continue;
            var payout = ctx.currency().deposit(esc, amount, "transfer:" + t.id + ":" + e.uuid);
            if (payout.ok()) {
                e.paid = true;
                esc.tell("§aTransfer " + t.id + " livrat — plata escortei: " + amount + " B.");
            }
        }
    }

    /** Chain broken — the prisoner (and hunt context) become a manhunt. */
    private void breakRide(TransferStore data, TransferRecord t, String reason) {
        t.status = "BROKEN";
        t.endedReason = reason;
        t.endedAt = now();
        ctx.transfers().write(data);
        PlayerGateway prisoner = onlineByUuid(t.prisonerUuid);
        if (prison != null) prison.rideChainBroke(t.prisonerUuid, prisoner, t.id);
        if (manhunts != null && prisoner != null) {
            var runner = new ManhuntRecord.Runner();
            runner.uuid = t.prisonerUuid;
            runner.name = t.prisonerName;
            runner.wasPrisoner = true;
            manhunts.begin(List.of(runner), prisoner.dimension(),
                    prisoner.x(), prisoner.y(), prisoner.z(),
                    "transfer " + t.id, "transfer:" + t.id);
        }
        closeCall(t, "broken:" + reason);
        for (var e : t.escorts) {
            var esc = onlineByUuid(e.uuid);
            if (esc != null) esc.tell("§cTransfer " + t.id + " s-a rupt (" + reason + ") — vânătoare deschisă.");
        }
        audit.record(AuditActions.PRISON_FUGITIVE, "transfer", "", t.id, t.prisonerUuid,
                AuditResults.SUCCESS, "ride_broken reason=" + reason);
    }

    /** An unclaimed ride ends: the prisoner goes back to custody. */
    private void returnToCustody(TransferStore data, TransferRecord t, PlayerGateway prisoner) {
        if (prisoner == null) {
            t.escortDeadlineAt = now() + waitMs(); // wait for the body
            ctx.transfers().write(data);
            return;
        }
        if (custody != null && custody.isCuffed(prisoner)) custody.releaseChainSilently(prisoner);
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(t.prisonerUuid);
        var home = homeStatus(t);
        if (rec != null && rec.status != home) {
            prisoner.teleport(t.originDimension, t.originX, t.originY, t.originZ);
            rec.status = home;
            ctx.prisonerRegister().write(reg);
        }
        t.status = "RETURNED";
        t.endedReason = "escort_wait_elapsed";
        t.endedAt = now();
        ctx.transfers().write(data);
        closeCall(t, "returned_to_custody");
        prisoner.tell("§7Transferul " + t.id + " a picat — ești înapoi în custodie.");
        audit.record(AuditActions.PRISON_RECAPTURE, "transfer", "", t.id, t.prisonerUuid,
                AuditResults.SUCCESS, "returned_to_custody");
    }

    /** Where a failed ride puts the prisoner — the custody they left. */
    private static PrisonerStatus homeStatus(TransferRecord t) {
        if ("IN_CAMP".equals(t.originStatus)) return PrisonerStatus.IN_CAMP;
        return PrisonerStatus.IN_CELL;
    }

    private void closeCall(TransferRecord t, String outcome) {
        if (dispatch != null)
            dispatch.closeForSource(DispatchService.SOURCE_TRANSFER, t.id, outcome);
    }

    // ------------------------------------------------------------ surfaces

    /** Live transfer for a prisoner/dispatch validation. */
    public synchronized TransferRecord liveTransfer(String id) {
        var t = store().find(id);
        return t != null && t.isLive() ? t : null;
    }

    /** Every live ride — the read-only snapshot a desk board renders. */
    public synchronized List<TransferRecord> liveTransfers() {
        var out = new ArrayList<TransferRecord>();
        for (var t : store().transfers) {
            if (t != null && t.isLive()) out.add(t);
        }
        return out;
    }

    /** `/straja transfer` — prisoner sees their ride; escorts see their job. */
    public synchronized void status(PlayerGateway player) {
        if (player == null || player.uuid() == null) return;
        String key = player.uuid().toString();
        TransferStore data = store();
        List<TransferRecord> mine = new ArrayList<>();
        for (var t : data.transfers) {
            if (t != null && t.isLive()
                    && (key.equalsIgnoreCase(t.prisonerUuid) || t.escort(key) != null)) mine.add(t);
        }
        if (mine.isEmpty()) {
            player.tell("Liniște — fără transfer activ pe numele tău.");
            return;
        }
        for (var t : mine) {
            boolean prisonerSide = key.equalsIgnoreCase(t.prisonerUuid);
            player.tell("Transfer " + t.id + " [" + t.status + "] " + t.prisonerName
                    + " → " + t.destLabel
                    + (prisonerSide ? "" : " — escorts: " + t.escorts.size()
                            + "/" + Math.max(1, ctx.policies().transferMaxEscorts)));
        }
    }

    /** `/straja transfer list` — live rides for on-duty members. */
    public synchronized void listFor(PlayerGateway player) {
        if (!players.isOnDutyGuard(player)) {
            player.refuse("straja.incident.work_rank", "straja.remedy.duty");
            return;
        }
        boolean any = false;
        for (var t : store().transfers) {
            if (t == null || !t.isLive()) continue;
            any = true;
            player.tell(t.id + " [" + t.status + "] " + t.prisonerName + " → " + t.destLabel
                    + " — escorts " + t.escorts.size() + "/" + Math.max(1, ctx.policies().transferMaxEscorts)
                    + (t.dispatchCallId.isBlank() ? "" : " apel " + t.dispatchCallId));
        }
        if (!any) player.tell("Fără transferuri în desfășurare.");
    }

    /** Officer call-off: the ride cancels and the prisoner returns home. */
    public synchronized boolean cancel(PlayerGateway actor, String transferId) {
        if (actor == null) return false;
        var level = players.permissionLevel(actor, players.state(actor));
        if (level != PermissionLevel.LIEUTENANT && level != PermissionLevel.COMMISSIONER) {
            actor.refuse("straja.manhunt.calloff_rank", "straja.remedy.inspector");
            return false;
        }
        TransferStore data = store();
        var t = data.find(transferId);
        if (t == null || !t.isLive()) {
            actor.refuse("straja.transfer.inactive", "straja.remedy.retry");
            return false;
        }
        PlayerGateway prisoner = onlineByUuid(t.prisonerUuid);
        if ("IN_PROGRESS".equals(t.status) && prisoner != null) {
            returnToCustody(data, t, prisoner);
        } else {
            // Absent body: drop the chain by uuid and settle the register —
            // an ESCORTED record would flag them fugitive at next login.
            if (custody != null) custody.releaseChainSilently(t.prisonerUuid);
            var reg = ctx.prisonerRegister().read();
            var rec = reg.prisoner(t.prisonerUuid);
            if (rec != null && rec.status == PrisonerStatus.ESCORTED) {
                rec.status = homeStatus(t);
                ctx.prisonerRegister().write(reg);
            }
            t.status = "CANCELLED";
            t.endedReason = "called_off";
            t.endedAt = now();
            ctx.transfers().write(data);
            closeCall(t, "called_off");
        }
        actor.tell("Transferul " + t.id + " a fost oprit.");
        audit.record(AuditActions.CAMP_TRANSFER, actor.name(), uuidOf(actor), t.id,
                t.prisonerUuid, AuditResults.SUCCESS, "called_off");
        return true;
    }

    // ------------------------------------------------------------ zones admin

    public boolean zoneAdd(PlayerGateway admin, String id, double radius, String name) {
        if (admin == null || id == null || id.isBlank()) return false;
        var data = ctx.setup().read();
        if (data.riskZones == null) data.riskZones = new java.util.LinkedHashMap<>();
        if (data.riskZones.containsKey(id)) {
            admin.refuse("straja.zone.exists", "straja.remedy.fix_retry", id);
            return false;
        }
        if (radius < 4 || radius > 512) {
            admin.refuse("straja.zone.radius_invalid", "straja.remedy.fix_retry");
            return false;
        }
        var zone = new SetupData.RiskZone();
        zone.id = id;
        zone.name = name == null ? "" : name.trim();
        zone.dimension = admin.dimension();
        zone.x = admin.x(); zone.y = admin.y(); zone.z = admin.z();
        zone.radius = radius;
        data.riskZones.put(id, zone);
        ctx.setup().write(data);
        admin.tell("Zonă riscantă " + id + " declarată (r=" + (int) radius + ").");
        audit.record(AuditActions.CAMP_SPAWN, admin.name(), uuidOf(admin), id, "",
                AuditResults.SUCCESS, "risk_zone r=" + (int) radius);
        return true;
    }

    public boolean zoneRemove(PlayerGateway admin, String id) {
        if (admin == null) return false;
        var data = ctx.setup().read();
        if (data.riskZones == null || data.riskZones.remove(id) == null) {
            admin.refuse("straja.zone.unknown", "straja.remedy.fix_retry", id);
            return false;
        }
        ctx.setup().write(data);
        admin.tell("Zonă riscantă " + id + " eliminată.");
        return true;
    }

    public void zoneList(PlayerGateway admin) {
        if (admin == null) return;
        var zones = ctx.setup().read().riskZones;
        if (zones == null || zones.isEmpty()) {
            admin.tell("Fără zone riscante declarate.");
            return;
        }
        for (var zone : zones.values()) {
            if (zone == null) continue;
            admin.tell(zone.id + " „" + (zone.name.isBlank() ? "-" : zone.name) + "” r="
                    + (int) zone.radius + " @ " + zone.dimension
                    + " x:" + (int) zone.x + " y:" + (int) zone.y + " z:" + (int) zone.z);
        }
    }

    // ------------------------------------------------------------ helpers

    private long waitMs() {
        return Math.max(1, ctx.policies().transferEscortWaitMinutes) * 60_000L;
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
