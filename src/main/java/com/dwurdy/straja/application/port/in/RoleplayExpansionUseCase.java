package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/** Server-authoritative inbound boundary for the connected RP expansion. */
public interface RoleplayExpansionUseCase {
    record IncidentView(String id, String priority, String status, String title,
                        String description, String location, String subject,
                        String leadCallsign, int supportingGuards, long expiresAt) {}

    record RosterEntry(String callsign, String name, String rank,
                       String operationalState, String incidentId) {}

    record BoloView(String id, String subject, String reason, String authority,
                    boolean arrestAuthority, long expiresAt, String incidentId) {}

    record ReputationView(String subject, int score, String band, boolean exact) {}
    record ReputationEventView(String id, String source, int delta, int scoreBefore,
                               int scoreAfter, long at, String actor, String reason,
                               boolean voided) {}

    record SearchSlot(int slot, ItemView item) {}

    record SearchView(String token, String targetName, List<SearchSlot> slots) {}

    List<IncidentView> activeIncidents(PlayerGateway viewer);
    List<RosterEntry> dutyRoster(PlayerGateway viewer);
    List<BoloView> activeBolos(PlayerGateway viewer);
    ReputationView reputation(PlayerGateway subject, PlayerGateway viewer);
    List<ReputationEventView> reputationHistory(PlayerGateway viewer, String subjectName);
    boolean correctReputation(PlayerGateway actor, String subjectName, int delta, String reason);

    boolean reportIncident(PlayerGateway citizen, String category, String description);
    boolean useWhistle(PlayerGateway guard);
    boolean acceptIncident(PlayerGateway guard, String incidentId);
    boolean joinIncident(PlayerGateway guard, String incidentId);
    boolean leaveIncident(PlayerGateway guard, String incidentId);
    boolean resolveIncident(PlayerGateway guard, String incidentId,
                            String resolution, String notes);

    boolean createBolo(PlayerGateway issuer, String subjectName, String reason,
                       String notes, String authority, String incidentId);
    boolean cancelBolo(PlayerGateway actor, String boloId);

    SearchView beginSearch(PlayerGateway guard, PlayerGateway target);
    boolean confiscate(PlayerGateway guard, String token, int slot, int amount,
                       String reason, String incidentId);
    boolean depositEvidence(PlayerGateway actor, String evidenceId);
    boolean returnEvidence(PlayerGateway actor, String evidenceId);
    boolean transferEvidence(PlayerGateway actor, String evidenceId,
                             String custodianName, String reason);
    boolean destroyEvidence(PlayerGateway actor, String evidenceId, String reason);

    boolean finalizeArrest(PlayerGateway jailer, String detaineeName,
                           String sentenceId, String notes);
    boolean recruitmentAllowed(PlayerGateway player);

    /** Called by the event adapter on final death only. */
    void recordFinalDeath(PlayerGateway killer, PlayerGateway victim);

    /** Called after hostile player damage, used for lawful lethal-force context. */
    void recordHostileDamage(PlayerGateway attacker, PlayerGateway victim);

    /** Called by the tick adapter for expiry and transient session cleanup. */
    void tick();

    /** Retries durable evidence-reference deliveries for a player who connected. */
    void deliverPendingEvidence(PlayerGateway player);

    /** Removes active assignments when duty/recruitment status changes. */
    void removeGuardAssignments(PlayerGateway guard, String reason);
}
