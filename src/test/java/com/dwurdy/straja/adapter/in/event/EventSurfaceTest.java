package com.dwurdy.straja.adapter.in.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level contract for the NeoForge event adapter: real NeoForge events
 * cannot be constructed without a running server, so this pins the handler
 * registration surface and the inbound-port routing statically. This is not
 * live/GameTest proof.
 */
class EventSurfaceTest {
    private static final Path SOURCE =
            Path.of("src/main/java/com/dwurdy/straja/adapter/in/event/StrajaEvents.java");

    private static String source() throws IOException {
        return Files.readString(SOURCE);
    }

    @Test
    void declaresLifecycleAndInteractionHandlers() throws IOException {
        String src = source();
        for (String event : new String[] {
                "PlayerLoggedInEvent", "PlayerLoggedOutEvent", "LivingDeathEvent",
                "EntityInteract", "RightClickBlock", "BreakEvent",
                "EntityPlaceEvent", "Detonate", "LivingIncomingDamageEvent"}) {
            assertTrue(src.contains(event), () -> "StrajaEvents must handle " + event);
        }
    }

    @Test
    void routesCustodyPrisonRoomThroughInboundPorts() throws IOException {
        String src = source();
        assertTrue(src.contains("custodyRoleplay()"), "custody must route via the inbound port");
        assertTrue(src.contains("prisonRoleplay()"), "prison must route via the inbound port");
        assertTrue(src.contains("roomRoleplay()"), "rooms must route via the inbound port");
        assertTrue(src.contains("archiveRoleplay()"), "archive must route via the inbound port");
        assertTrue(src.contains("recoverOnLogin"), "login recovery must be invoked");
        assertTrue(src.contains("recoverOnLogout"), "logout recovery must be invoked");
        assertTrue(src.contains("recoverAfterDeath"), "death recovery must be invoked");
    }

    @Test
    void loginRecoveryCoversEveryPersistedAggregateThroughPorts() throws IOException {
        String src = source();
        assertTrue(src.contains("guardDuty().recoverOnLogin"),
                "stale duty must close through the duty port");
        assertTrue(src.contains("custodyRoleplay().recoverOnLogin"),
                "custody recovery must route via the port");
        assertTrue(src.contains("prisonRoleplay().recoverOnLogin"),
                "prison recovery must route via the port");
        assertTrue(src.contains("complaintRoleplay().claimPendingRewards"),
                "complaint rewards must route via the port");
        assertTrue(src.contains("missionRoleplay().deliverPendingRewards"),
                "mission reward recovery must route via the port");
        assertTrue(src.contains("fineRoleplay().recoverOnLogin"),
                "arrest reward recovery must route via the port");
        assertTrue(src.contains("archiveRoleplay().deliverPending"),
                "archive deliveries must route via the port");
        assertTrue(src.contains("roomRoleplay().assignAutomatically"),
                "room assignment must route via the port");
        assertTrue(src.contains("roomRoleplay().processWaitlist"),
                "room waitlist must route via the port");
    }

    @Test
    void volatileTokensAndSessionsArePurgedOnTick() throws IOException {
        String src = source();
        assertTrue(src.contains("formSessions().purgeExpired"),
                "form sessions must be purged on tick");
        assertTrue(src.contains("purgeExpiredTokens"),
                "NPC action tokens must be purged on tick");
    }

    @Test
    void toolItemUseFollowUpCannotDoubleFireAirGestures() throws IOException {
        String src = source();
        assertTrue(src.contains("toolClickHandledAt"),
                "the item-use packet that trails a tool block/entity click must be deduped");
        assertTrue(src.contains("getGameTime"),
                "the dedupe must be tick-scoped");
    }

    @Test
    void staysOffConcreteServicesAndPersistence() throws IOException {
        String src = source();
        assertFalse(src.contains("com.dwurdy.straja.application.service."),
                "StrajaEvents must not reference concrete application services");
        assertFalse(src.contains("com.dwurdy.straja.adapter.out.persistence"),
                "StrajaEvents must not reference persistence adapters");
    }
}
