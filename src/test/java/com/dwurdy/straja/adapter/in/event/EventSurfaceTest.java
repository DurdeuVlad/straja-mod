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
    void customNpcAdminAttackCancelsAtEarlyAttackBoundary() throws IOException {
        String src = source();
        int handler = src.indexOf("void onAttackEntity(");
        int route = src.indexOf("NpcPresentationRuntime.handleCustomNpcAdminAttack(player, event.getTarget())", handler);
        int cancel = src.indexOf("event.setCanceled(true)", route);
        assertTrue(handler >= 0 && route > handler && cancel > route);
        int annotation = src.lastIndexOf("@SubscribeEvent(priority = EventPriority.HIGHEST)", handler);
        assertTrue(annotation >= 0 && handler - annotation < 250);
        assertTrue(src.substring(handler, route).contains("isClientSide()"));
        assertTrue(src.substring(handler, route).contains("ServerPlayer player"));
    }

    @Test
    void attackAuthorizationRequiresServerOperatorAndMainHandWand() throws IOException {
        String runtime = Files.readString(Path.of(
                "src/main/java/com/dwurdy/straja/bootstrap/NpcPresentationRuntime.java"));
        assertTrue(java.util.regex.Pattern.compile(
                "player\\.hasPermissions\\(2\\)\\s*&& player\\.getMainHandItem\\(\\)"
                        + "\\.is\\(StrajaItems\\.NPC_WAND\\.get\\(\\)\\)")
                .matcher(runtime).find());
        String provider = Files.readString(Path.of(
                "src/main/java/com/dwurdy/straja/adapter/out/npc/customnpcs/CustomNpcsNpcSurfaceProvider.java"));
        assertTrue(provider.contains("decision.hostId().toString()"),
                "selector identity must come from the server attack target");
        assertFalse(provider.contains("registerInteractionListener(eventBus, damagedEventType"),
                "post-damage interception cannot own the admin gesture");
        String interaction = provider.substring(provider.indexOf("private void onCustomNpcsEvent("),
                provider.indexOf("private static boolean isSupportedInteractionEvent("));
        assertFalse(interaction.contains("openAdminSelector("),
                "right-click must not enter the admin selector");
        String selector = provider.substring(provider.indexOf("private void openAdminSelector("),
                provider.indexOf("private void openAdminDuplicateCleanup("));
        assertFalse(selector.contains("provisioning.assignIfRevisionMatches("),
                "the selector must not assign before confirmation");
    }

    @Test
    void activeProvisionedBindingPrecedesLegacyNpcRegistration() throws IOException {
        String src = source();
        int provisionedRoute = src.indexOf(
                "if (NpcPresentationRuntime.hasBindingForHost(event.getTarget().getStringUUID())) return;");
        int legacyRoute = src.indexOf(
                "var registration = runtime.npcRegistry().registration(event.getTarget().getStringUUID());");

        assertTrue(provisionedRoute >= 0, "provider-owned CustomNPCs must reach its native GUI handler");
        assertTrue(legacyRoute > provisionedRoute,
                "legacy role chat must not cancel an active provider-owned interaction");
    }

    @Test
    void eventRoleLookupUsesCanonicalProvisionedRoleBeforeLegacyRole() throws IOException {
        String src = source();
        int resolver = src.indexOf("NpcPresentationRuntime.assignedRoleForHost(entity.getStringUUID())");
        int legacyLookup = src.indexOf(
                "runtime.npcRegistry().registration(entity.getStringUUID())", resolver);

        assertTrue(resolver >= 0, "jailer events must resolve assignments from the provider-neutral binding store");
        assertTrue(legacyLookup > resolver, "legacy roles are fallback only when no provider binding exists");
        assertTrue(src.contains("npcRoleOf(runtime, event.getEntity())"),
                "the jailer immunity boundary must use canonical role lookup");
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
