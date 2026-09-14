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
    private static final int MAX_LETHAL_EVENT_HISTORY = 32;

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
    public Long resuscitationDeadlineAt;
    public Long unconsciousCustodyDeadlineAt;
    public Long transportDeadlineAt;
    public Long jailDeliveryDeadlineAt;
    public Long jailRevivalAt;
    public int resuscitationProgress;

    /**
     * Downed time retained while the normal downed clock is paused by carry or
     * resuscitation.  This is a duration, not a wall-clock deadline: elapsed
     * server time while paused must not consume it.
     */
    public long pausedDownedRemainingMs;

    public String carrierId = "";
    /** UUID of the player currently performing resuscitation, if any. */
    public String resuscitatorId = "";
    public String restraintActorId = "";
    public String custodyActorId = "";
    public String destination = "";

    /** Last accepted transition identity; retries with this id are no-ops. */
    public String transitionId = "";

    /** Last lethal-event identity/outcome; protects non-Straja provider claims. */
    public String lastLethalEventId = "";
    public String lastLethalOutcome = "";
    public StateProvider lastLethalProvider = StateProvider.SYSTEM;

    /** Bounded replay protection for lethal events older than the latest one. */
    public List<String> lethalEventHistory = new ArrayList<>();
    public List<String> lethalEventOwnedIds = new ArrayList<>();

    public boolean hasLethalEvent(String eventId) {
        return eventId != null && !eventId.isBlank()
                && lethalEventHistory != null && lethalEventHistory.contains(eventId);
    }

    public boolean ownsLethalEvent(String eventId) {
        return eventId != null && !eventId.isBlank()
                && lethalEventOwnedIds != null && lethalEventOwnedIds.contains(eventId);
    }

    public void rememberLethalEvent(String eventId, boolean owned) {
        if (eventId == null || eventId.isBlank()) return;
        if (lethalEventHistory == null) lethalEventHistory = new ArrayList<>();
        if (lethalEventOwnedIds == null) lethalEventOwnedIds = new ArrayList<>();
        lethalEventHistory.remove(eventId);
        lethalEventHistory.add(eventId);
        lethalEventOwnedIds.remove(eventId);
        if (owned) lethalEventOwnedIds.add(eventId);
        while (lethalEventHistory.size() > MAX_LETHAL_EVENT_HISTORY) {
            String removed = lethalEventHistory.remove(0);
            lethalEventOwnedIds.remove(removed);
        }
    }

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
        if (condition == PlayerCondition.RESUSCITATING && blank(resuscitatorId)) {
            result.add("resuscitator_missing");
        } else if (condition != PlayerCondition.RESUSCITATING && !blank(resuscitatorId)) {
            result.add("orphan_resuscitator");
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
        if (condition == PlayerCondition.DOWNED) {
            boolean paused = transport == TransportStatus.CARRIED;
            if (paused) {
                if (!positive(pausedDownedRemainingMs)) result.add("paused_downed_time_missing");
                if (downedDeadlineAt != null) result.add("paused_downed_deadline_present");
        } else if (!positive(downedDeadlineAt)) {
                result.add("downed_deadline_missing");
            }
        } else if (downedDeadlineAt != null) {
            result.add("orphan_downed_deadline");
        }
        if (condition == PlayerCondition.RESUSCITATING) {
            if (!positive(resuscitationDeadlineAt)) result.add("resuscitation_deadline_missing");
            if (!positive(pausedDownedRemainingMs)) result.add("paused_downed_time_missing");
        } else if (resuscitationDeadlineAt != null) {
            result.add("orphan_resuscitation_deadline");
        }
        if (condition != PlayerCondition.DOWNED && condition != PlayerCondition.RESUSCITATING
                && pausedDownedRemainingMs != 0) {
            result.add("orphan_paused_downed_time");
        }
        if (condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                && custody != CustodyStatus.JAILED
                && !positive(unconsciousCustodyDeadlineAt)) {
            result.add("unconscious_custody_deadline_missing");
        } else if (condition != PlayerCondition.UNCONSCIOUS_CUSTODY
                && unconsciousCustodyDeadlineAt != null) {
            result.add("orphan_unconscious_custody_deadline");
        }
        if (transport == TransportStatus.CARRIED && !positive(transportDeadlineAt)) {
            result.add("transport_deadline_missing");
        } else if (transport != TransportStatus.CARRIED && transportDeadlineAt != null) {
            result.add("orphan_transport_deadline");
        }
        if (condition == PlayerCondition.DEAD && transport == TransportStatus.CARRIED) {
            result.add("dead_player_carried");
        }
        if (custody == CustodyStatus.ARRESTED) {
            if (!positive(jailDeliveryDeadlineAt) && condition != PlayerCondition.DEAD) {
                result.add("jail_delivery_deadline_missing");
            } else if (condition == PlayerCondition.DEAD && jailDeliveryDeadlineAt != null) {
                result.add("orphan_jail_delivery_deadline");
            }
        } else if (jailDeliveryDeadlineAt != null) {
            result.add("orphan_jail_delivery_deadline");
        }
        if (custody == CustodyStatus.JAILED && condition == PlayerCondition.UNCONSCIOUS_CUSTODY) {
            // jailRevivalAt is policy-dependent and is validated by the deadline engine.
        } else if (jailRevivalAt != null) {
            result.add("orphan_jail_revival_deadline");
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

    private static boolean positive(long value) {
        return value > 0;
    }
}
