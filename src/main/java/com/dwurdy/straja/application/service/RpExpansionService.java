package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.BoloAuthority;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.Incident;
import com.dwurdy.straja.domain.model.IncidentPriority;
import com.dwurdy.straja.domain.model.IncidentResolution;
import com.dwurdy.straja.domain.model.IncidentStatus;
import com.dwurdy.straja.domain.model.IncidentType;
import com.dwurdy.straja.domain.model.ReputationState;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Sentence;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Small facade for adapters; each concern remains in its own application service. */
public final class RpExpansionService implements RoleplayExpansionUseCase {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final IncidentService incidents;
    private final BoloService bolos;
    private final EvidenceService evidence;
    private final ArrestRecordService arrests;
    private final ReputationService reputation;

    public RpExpansionService(StrajaContext ctx, PlayerService players,
                              IncidentService incidents, BoloService bolos,
                              EvidenceService evidence, ArrestRecordService arrests,
                              ReputationService reputation) {
        this.ctx = ctx;
        this.players = players;
        this.incidents = incidents;
        this.bolos = bolos;
        this.evidence = evidence;
        this.arrests = arrests;
        this.reputation = reputation;
    }

    @Override
    public List<IncidentView> activeIncidents(PlayerGateway viewer) {
        if (viewer == null || (!players.isOnDutyGuard(viewer) && !players.isCommissioner(viewer))) {
            return List.of();
        }
        return incidents.active().stream().map(this::incidentView).toList();
    }

    @Override
    public List<RosterEntry> dutyRoster(PlayerGateway viewer) {
        if (viewer == null || (!players.isOnDutyGuard(viewer) && !players.isCommissioner(viewer))) {
            return List.of();
        }
        List<RosterEntry> result = new ArrayList<>();
        for (PlayerGateway player : ctx.server().onlinePlayers()) {
            if (!players.isOnDutyGuard(player)) continue;
            GuardState state = players.state(player);
            String callsign = ensureCallsign(player, state);
            String incidentId = incidentFor(player);
            result.add(new RosterEntry(callsign, player.name(), ctx.policies().rankName(state.rank),
                    operationalState(player, state, incidentId), incidentId));
        }
        result.sort(java.util.Comparator.comparing(RosterEntry::callsign));
        return List.copyOf(result);
    }

    @Override
    public List<BoloView> activeBolos(PlayerGateway viewer) {
        if (viewer == null || (!players.isOnDutyGuard(viewer) && !players.isCommissioner(viewer))) {
            return List.of();
        }
        return bolos.active().stream().map(record -> new BoloView(
                record.id, record.subjectName, record.reason,
                record.authority == null ? "INFORMATION_ONLY" : record.authority.name(),
                bolos.hasArrestAuthority(record), record.expiresAt, record.linkedIncidentId)).toList();
    }

    @Override
    public ReputationView reputation(PlayerGateway subject, PlayerGateway viewer) {
        if (subject == null || viewer == null) return null;
        boolean exact = players.isCommissioner(viewer) || Objects.equals(subject.uuid(), viewer.uuid());
        boolean allowed = exact || players.isOnDutyGuard(viewer);
        if (!allowed) return null;
        ReputationState state = reputation.state(subject);
        return new ReputationView(subject.name(), exact ? state.score : 0, state.band, exact);
    }

    @Override
    public List<ReputationEventView> reputationHistory(PlayerGateway viewer, String subjectName) {
        if (viewer == null || !players.isCommissioner(viewer)) return List.of();
        PlayerGateway subject = players.findPlayer(subjectName);
        if (subject == null) return List.of();
        return reputation.history(viewer, subject).stream().map(event ->
                new ReputationEventView(event.id, event.sourceType + ":" + event.sourceRecordId,
                        event.delta, event.scoreBefore, event.scoreAfter, event.at,
                        event.actorName, event.reason, event.voided)).toList();
    }

    @Override
    public boolean correctReputation(PlayerGateway actor, String subjectName,
                                     int delta, String reason) {
        PlayerGateway subject = players.findPlayer(subjectName);
        return reputation.commend(actor, subject, delta, reason) != null;
    }

    @Override
    public boolean reportIncident(PlayerGateway citizen, String category, String description) {
        return incidents.createCitizenReport(citizen, category, description) != null;
    }

    @Override
    public boolean useWhistle(PlayerGateway guard) {
        return incidents.useWhistle(guard) != null;
    }

    @Override
    public boolean acceptIncident(PlayerGateway guard, String incidentId) {
        return incidents.accept(guard, incidentId);
    }

    @Override
    public boolean joinIncident(PlayerGateway guard, String incidentId) {
        return incidents.join(guard, incidentId);
    }

    @Override
    public boolean leaveIncident(PlayerGateway guard, String incidentId) {
        return incidents.leave(guard, incidentId);
    }

    @Override
    public boolean resolveIncident(PlayerGateway guard, String incidentId,
                                   String resolution, String notes) {
        return incidents.resolve(guard, incidentId, resolution, notes);
    }

    @Override
    public boolean createBolo(PlayerGateway issuer, String subjectName, String reason,
                              String notes, String authority, String incidentId) {
        PlayerGateway subject = players.findPlayer(subjectName);
        if (subject == null) {
            issuer.tell("Subiectul trebuie să fie online pentru această emitere.");
            return false;
        }
        BoloAuthority parsed;
        try {
            parsed = BoloAuthority.valueOf((authority == null ? "" : authority.trim())
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            parsed = BoloAuthority.INFORMATION_ONLY;
        }
        return bolos.create(issuer, subject, reason, notes, parsed, incidentId) != null;
    }

    @Override
    public boolean cancelBolo(PlayerGateway actor, String boloId) {
        return bolos.cancel(actor, boloId);
    }

    @Override
    public SearchView beginSearch(PlayerGateway guard, PlayerGateway target) {
        return evidence.beginSearch(guard, target);
    }

    @Override
    public boolean confiscate(PlayerGateway guard, String token, int slot, int amount,
                              String reason, String incidentId) {
        return evidence.confiscate(guard, token, slot, amount, reason, incidentId);
    }

    @Override
    public boolean depositEvidence(PlayerGateway actor, String evidenceId) {
        return evidence.deposit(actor, evidenceId);
    }

    @Override
    public boolean returnEvidence(PlayerGateway actor, String evidenceId) {
        return evidence.returnToOwner(actor, evidenceId);
    }

    @Override
    public boolean transferEvidence(PlayerGateway actor, String evidenceId,
                                    String custodianName, String reason) {
        PlayerGateway recipient = players.findPlayer(custodianName);
        return evidence.transfer(actor, evidenceId, recipient, reason);
    }

    @Override
    public boolean destroyEvidence(PlayerGateway actor, String evidenceId, String reason) {
        return evidence.destroy(actor, evidenceId, reason);
    }

    @Override
    public boolean finalizeArrest(PlayerGateway jailer, String detaineeName,
                                  String sentenceId, String notes) {
        PlayerGateway detainee = players.findPlayer(detaineeName);
        return arrests.finalizeFromJailer(jailer, detainee, sentenceId, notes);
    }

    @Override
    public boolean recruitmentAllowed(PlayerGateway player) {
        return reputation.recruitmentAllowed(player);
    }

    @Override
    public void recordFinalDeath(PlayerGateway killer, PlayerGateway victim) {
        reputation.recordFinalDeath(killer, victim);
    }

    @Override
    public void recordHostileDamage(PlayerGateway attacker, PlayerGateway victim) {
        reputation.recordHostileDamage(attacker, victim);
    }

    @Override
    public void tick() {
        incidents.expire();
        bolos.expire();
        evidence.purgeExpiredSessions();
    }

    @Override
    public void deliverPendingEvidence(PlayerGateway player) {
        evidence.deliverPending(player);
    }

    @Override
    public void removeGuardAssignments(PlayerGateway guard, String reason) {
        incidents.removeAssignments(guard, reason);
    }

    public ArrestRecordService arrests() { return arrests; }
    public EvidenceService evidence() { return evidence; }
    public ReputationService reputation() { return reputation; }
    public IncidentService incidents() { return incidents; }
    public BoloService bolos() { return bolos; }

    public void startArrestRecord(Sentence sentence) {
        arrests.startForSentence(sentence);
    }

    public void finalizeArrestRecord(Sentence sentence, String notes) {
        arrests.finalizeForSentence(sentence, notes);
    }

    private IncidentView incidentView(Incident incident) {
        String location = incident.placeLabel == null || incident.placeLabel.isBlank()
                ? String.format(Locale.ROOT, "(%.0f, %.0f, %.0f)",
                    incident.x, incident.y, incident.z)
                : incident.placeLabel;
        String lead = incident.leadGuardName == null ? "" : incident.leadGuardName;
        if (incident.leadGuardUuid != null && !incident.leadGuardUuid.isBlank()) {
            PlayerGateway leadPlayer = ctx.server().findPlayer(incident.leadGuardUuid);
            if (leadPlayer != null) {
                GuardState leadState = players.state(leadPlayer.uuid());
                if (leadState != null) lead = ensureCallsign(leadPlayer, leadState);
            }
        }
        return new IncidentView(incident.id,
                incident.priority == null ? "ROUTINE" : incident.priority.name(),
                incident.status == null ? "OPEN" : incident.status.name(),
                incident.title, incident.description, location,
                incident.subjectName, lead, incident.supportingGuardUuids.size(),
                incident.expiresAt);
    }

    private String ensureCallsign(PlayerGateway player, GuardState state) {
        String current = state.callsign == null ? "" : state.callsign.trim();
        boolean currentUsedElsewhere = false;
        for (var known : ctx.players().knownIds()) {
            if (known.equals(player.uuid())) continue;
            GuardState knownState = ctx.players().read(known);
            if (knownState != null && current.equals(knownState.callsign)) {
                currentUsedElsewhere = true;
                break;
            }
        }
        if (!currentUsedElsewhere && current.matches("S-\\d{3,6}")) return current;
        int next = 1;
        for (var known : ctx.players().knownIds()) {
            GuardState knownState = ctx.players().read(known);
            if (knownState == null || knownState.callsign == null) continue;
            try {
                next = Math.max(next, Integer.parseInt(knownState.callsign.substring(2)) + 1);
            } catch (RuntimeException ignored) {
                // Legacy/non-standard callsigns do not participate in allocation.
            }
        }
        state.callsign = String.format(Locale.ROOT, "S-%03d", next);
        players.save(player.uuid(), state);
        return state.callsign;
    }

    private String incidentFor(PlayerGateway player) {
        String uuid = player.uuid().toString();
        return incidents.active().stream()
                .filter(i -> uuid.equals(i.leadGuardUuid) || i.supportingGuardUuids.contains(uuid))
                .map(i -> i.id).findFirst().orElse("");
    }

    private String operationalState(PlayerGateway player, GuardState state, String incidentId) {
        if (!incidentId.isBlank()) return "ON_INCIDENT";
        String uuid = player.uuid().toString();
        for (var custody : ctx.custody().read().states.values()) {
            if (custody != null && uuid.equals(custody.carrierId)) return "TRANSPORTING_CUSTODY";
        }
        if ("SPECIAL".equalsIgnoreCase(state.mode)) return "SPECIAL_DUTY";
        var hq = ctx.setup().read().location("hq");
        if (hq != null && player.dimension().equals(hq.dimension)) {
            double dx = player.x() - hq.x, dy = player.y() - hq.y, dz = player.z() - hq.z;
            if (dx * dx + dy * dy + dz * dz <= 12 * 12) return "AT_HEADQUARTERS";
        }
        return "AVAILABLE";
    }
}
