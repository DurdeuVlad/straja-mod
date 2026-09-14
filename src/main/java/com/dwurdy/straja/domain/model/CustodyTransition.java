package com.dwurdy.straja.domain.model;

/**
 * Pure input to the custody state machine.  Adapters resolve player identity,
 * capability, items, and world context before creating one of these values.
 */
public record CustodyTransition(
        String id,
        Action action,
        long at,
        String actorId,
        String carrierId,
        String destination,
        StateProvider provider,
        String source,
        int progress) {

    public enum Action {
        ENTER_DOWNED,
        START_RESUSCITATION,
        ADVANCE_RESUSCITATION,
        APPLY_ROPE,
        APPLY_CUFFS,
        START_CARRY,
        STOP_CARRY,
        APPLY_BLINDFOLD,
        REMOVE_BLINDFOLD,
        WAKE,
        RESOLVE_UNCONSCIOUS_DEADLINE,
        DELIVER_TO_JAIL,
        REVIVE_IN_JAIL,
        RELEASE_RESTRAINT,
        RELEASE_CUSTODY,
        DIE
    }

    public CustodyTransition {
        id = id == null ? "" : id.trim();
        actorId = actorId == null ? "" : actorId.trim();
        carrierId = carrierId == null ? "" : carrierId.trim();
        destination = destination == null ? "" : destination.trim();
        provider = provider == null ? StateProvider.SYSTEM : provider;
        source = source == null ? "" : source.trim();
    }

    public static CustodyTransition of(String id, Action action, long at, String actorId) {
        return new CustodyTransition(id, action, at, actorId, "", "",
                StateProvider.SYSTEM, "", 0);
    }
}
