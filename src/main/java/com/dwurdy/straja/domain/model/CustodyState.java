package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical per-player downed/custody state.
 *
 * <p>Each gameplay dimension is represented independently.  In particular,
 * {@link TransportStatus#CARRIED} never changes custody or condition, and
 * {@link RestraintStatus#ROPE_BOUND} and {@link RestraintStatus#CUFFED} have
 * different custody meanings.  The object is intentionally a simple public
 * data model so the existing Gson-backed {@code CustodyStore} can persist it
 * without a second serializer.</p>
 */
public class CustodyState {
    public String playerId = "";
    public String playerUuid = "";
    public String playerName = "";

    public PlayerCondition condition = PlayerCondition.ALIVE;
    public CustodyStatus custody = CustodyStatus.FREE;
    public TransportStatus transport = TransportStatus.NONE;
    public RestraintStatus restraint = RestraintStatus.NONE;
    public VisionStatus vision = VisionStatus.NORMAL;

    /** Provider and source are retained separately for provenance checks. */
    public StateProvider provider = StateProvider.SYSTEM;
    public String source = "";
    public String sourceUuid = "";

    /** Timestamp at which the current state was entered. */
    public long enteredAt;

    /** Deadlines are absolute epoch milliseconds; null means not applicable. */
    public Long downedDeadlineAt;
    /**
     * Remaining downed time captured when the normal death timer is paused.
     * A null downed deadline plus this value is the persisted paused form of
     * the timer (carry or active resuscitation), so reconnects cannot reset it.
     */
    public Long pausedDownedRemainingMs;
    public Long resuscitationDeadlineAt;
    public Long unconsciousCustodyDeadlineAt;
    public Long transportDeadlineAt;
    public Long jailDeliveryDeadlineAt;
    public Long jailRevivalAt;
    public int resuscitationProgress;

    public String carrierId = "";
    public String restraintActorId = "";
    public String custodyActorId = "";
    public String destination = "";

    /** Last accepted transition identity; retries with this id are no-ops. */
    public String transitionId = "";

    /**
     * Returns structural violations without applying policy-specific rules.
     * Persistence recovery can use this to fail closed before touching a
     * player or world object.
     */
    public List<String> violations() {
        var result = new ArrayList<String>();
        if (condition == null) result.add("condition_missing");
        if (custody == null) result.add("custody_missing");
        if (transport == null) result.add("transport_missing");
        if (restraint == null) result.add("restraint_missing");
        if (vision == null) result.add("vision_missing");
        if (provider == null) result.add("provider_missing");
        if (transport == TransportStatus.CARRIED && blank(carrierId)) {
            result.add("carrier_missing");
        }
        if (transport == TransportStatus.NONE && !blank(carrierId)) {
            result.add("orphan_carrier");
        }
        if (restraint == RestraintStatus.NONE && !blank(restraintActorId)) {
            result.add("orphan_restraint_actor");
        }
        if (custody == CustodyStatus.FREE && !blank(custodyActorId)) {
            result.add("orphan_custody_actor");
        }
        if (vision == VisionStatus.BLINDFOLDED && restraint == RestraintStatus.NONE) {
            result.add("blindfold_without_restraint");
        }
        if (restraint == RestraintStatus.ROPE_BOUND && custody != CustodyStatus.HOSTAGE) {
            result.add("rope_without_hostage_custody");
        }
        if (restraint == RestraintStatus.CUFFED
                && custody != CustodyStatus.ARRESTED && custody != CustodyStatus.JAILED) {
            result.add("cuffs_without_police_custody");
        }
        if (condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                && (restraint == RestraintStatus.NONE || custody == CustodyStatus.FREE)) {
            result.add("unconscious_custody_without_valid_control_context");
        }
        if (condition == PlayerCondition.CONSCIOUS_RESTRAINED
                && (restraint == RestraintStatus.NONE || custody == CustodyStatus.FREE)) {
            result.add("conscious_restrained_without_valid_control_context");
        }
        if (condition == PlayerCondition.DOWNED
                && !positive(downedDeadlineAt) && !positive(pausedDownedRemainingMs)) {
            result.add("downed_deadline_missing");
        }
        if (condition == PlayerCondition.DOWNED && transport == TransportStatus.CARRIED
                && downedDeadlineAt != null) {
            result.add("downed_timer_must_be_paused_while_carried");
        }
        if (pausedDownedRemainingMs != null && pausedDownedRemainingMs <= 0) {
            result.add("paused_downed_time_invalid");
        }
        if (condition != PlayerCondition.DOWNED && condition != PlayerCondition.RESUSCITATING
                && pausedDownedRemainingMs != null) {
            result.add("orphan_paused_downed_time");
        }
        if (condition == PlayerCondition.RESUSCITATING && !positive(resuscitationDeadlineAt)) {
            result.add("resuscitation_deadline_missing");
        }
        if (condition == PlayerCondition.RESUSCITATING
                && !positive(pausedDownedRemainingMs)) {
            result.add("resuscitation_downed_time_missing");
        }
        if (condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                && custody != CustodyStatus.JAILED
                && !positive(unconsciousCustodyDeadlineAt)) {
            result.add("unconscious_custody_deadline_missing");
        }
        if (transport == TransportStatus.CARRIED && !positive(transportDeadlineAt)) {
            result.add("transport_deadline_missing");
        }
        if (resuscitationProgress < 0 || resuscitationProgress > 100) {
            result.add("resuscitation_progress_out_of_range");
        }
        return List.copyOf(result);
    }

    public boolean wellFormed() {
        return violations().isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
