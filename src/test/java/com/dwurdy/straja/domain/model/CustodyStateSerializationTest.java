package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

class CustodyStateSerializationTest {
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    @Test
    void everyCustodyStateFieldRoundTrips() {
        var state = new CustodyState();
        state.playerId = "player-key";
        state.playerUuid = "11111111-1111-1111-1111-111111111111";
        state.playerName = "Player";
        state.condition = PlayerCondition.UNCONSCIOUS_CUSTODY;
        state.custody = CustodyStatus.ARRESTED;
        state.transport = TransportStatus.CARRIED;
        state.restraint = RestraintStatus.CUFFED;
        state.vision = VisionStatus.BLINDFOLDED;
        state.provider = StateProvider.NATIVE;
        state.source = "baton";
        state.sourceUuid = "22222222-2222-2222-2222-222222222222";
        state.enteredAt = 10;
        state.downedDeadlineAt = 20L;
        state.resuscitationDeadlineAt = 30L;
        state.unconsciousCustodyDeadlineAt = 40L;
        state.transportDeadlineAt = 50L;
        state.jailDeliveryDeadlineAt = 60L;
        state.jailRevivalAt = 70L;
        state.resuscitationProgress = 65;
        state.carrierId = "carrier";
        state.restraintActorId = "guard";
        state.custodyActorId = "guard";
        state.destination = "castle-jail";
        state.transitionId = "transition-7";

        var loaded = gson.fromJson(gson.toJson(state), CustodyState.class);
        assertEquals(state.playerId, loaded.playerId);
        assertEquals(state.playerUuid, loaded.playerUuid);
        assertEquals(state.playerName, loaded.playerName);
        assertEquals(state.condition, loaded.condition);
        assertEquals(state.custody, loaded.custody);
        assertEquals(state.transport, loaded.transport);
        assertEquals(state.restraint, loaded.restraint);
        assertEquals(state.vision, loaded.vision);
        assertEquals(state.provider, loaded.provider);
        assertEquals(state.source, loaded.source);
        assertEquals(state.sourceUuid, loaded.sourceUuid);
        assertEquals(state.enteredAt, loaded.enteredAt);
        assertEquals(state.downedDeadlineAt, loaded.downedDeadlineAt);
        assertEquals(state.resuscitationDeadlineAt, loaded.resuscitationDeadlineAt);
        assertEquals(state.unconsciousCustodyDeadlineAt, loaded.unconsciousCustodyDeadlineAt);
        assertEquals(state.transportDeadlineAt, loaded.transportDeadlineAt);
        assertEquals(state.jailDeliveryDeadlineAt, loaded.jailDeliveryDeadlineAt);
        assertEquals(state.jailRevivalAt, loaded.jailRevivalAt);
        assertEquals(state.resuscitationProgress, loaded.resuscitationProgress);
        assertEquals(state.carrierId, loaded.carrierId);
        assertEquals(state.restraintActorId, loaded.restraintActorId);
        assertEquals(state.custodyActorId, loaded.custodyActorId);
        assertEquals(state.destination, loaded.destination);
        assertEquals(state.transitionId, loaded.transitionId);
    }

    @Test
    void canonicalStateRoundTripsInsideExistingCustodyAggregate() {
        var store = new CustodyStore();
        var state = new CustodyState();
        state.playerId = "player-key";
        state.condition = PlayerCondition.DOWNED;
        state.custody = CustodyStatus.FREE;
        state.downedDeadlineAt = 1234L;
        state.transitionId = "down-1";
        store.states.put(state.playerId, state);

        var loaded = gson.fromJson(gson.toJson(store), CustodyStore.class);
        assertNotNull(loaded.states.get("player-key"));
        assertEquals(PlayerCondition.DOWNED, loaded.states.get("player-key").condition);
        assertEquals(1234L, loaded.states.get("player-key").downedDeadlineAt);
        assertEquals("down-1", loaded.states.get("player-key").transitionId);
    }
}
