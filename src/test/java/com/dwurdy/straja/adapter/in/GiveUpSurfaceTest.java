package com.dwurdy.straja.adapter.in;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source-level contracts for the DC-015 player-facing integration. */
class GiveUpSurfaceTest {
    private static final Path SRC = Path.of("src/main/java/com/dwurdy/straja");
    private static final Path LANG = Path.of("src/main/resources/assets/straja/lang");

    private static String source(String relative) throws IOException {
        return Files.readString(SRC.resolve(relative));
    }

    @Test
    void formActionAndValidationAreBoundedToGiveUp() throws IOException {
        String port = source("application/port/in/FormSessionUseCase.java");
        String service = source("application/service/FormSessionService.java");
        String menu = source("adapter/in/form/StrajaFormMenu.java");

        assertTrue(port.contains("GIVE_UP(\"give-up\")"));
        assertTrue(service.contains("action == Action.GIVE_UP ? List.of() : null"));
        assertTrue(service.contains("Action.GIVE_UP, Action.OTHER_REQUEST"));
        assertTrue(menu.contains("count < 0"),
                "the native menu must accept an empty server-issued field list");
    }

    @Test
    void authenticatedSubmissionRoutesOnlyThroughCustodyPort() throws IOException {
        String payloads = source("adapter/in/form/FormPayloads.java");
        String router = source("adapter/in/form/FormSubmissionRouter.java");
        String runtime = source("bootstrap/StrajaRuntime.java");

        assertTrue(payloads.contains("context.player() instanceof ServerPlayer"));
        assertTrue(payloads.contains("FormSessionBridge.consume"));
        assertTrue(router.contains("CustodyRoleplayUseCase custody"));
        assertTrue(router.contains("case GIVE_UP"));
        assertTrue(router.contains("custody.giveUp(gateway, true)"));
        assertTrue(router.contains("straja.give_up.stale"));
        assertTrue(runtime.contains("instance.adminTools, instance.custody)::submit"));
        assertFalse(router.contains("application.service."));
    }

    @Test
    void eventOfferIsOneOffButResetsWhenEligibilityIsLost() throws IOException {
        String events = source("adapter/in/event/StrajaEvents.java");

        assertTrue(events.contains("giveUpOffered"));
        assertTrue(events.contains("giveUpSessionIds"));
        assertTrue(events.contains("giveUpEligibility(gateway).eligible()"));
        assertTrue(events.contains("giveUpOffered.add(playerId)"));
        assertTrue(events.contains("FormSessionBridge.translationKey(\"straja.give_up.title\")"));
        assertTrue(events.contains("FormSessionBridge.translationKey(\"straja.give_up.confirmation\")"));
        assertTrue(events.indexOf("giveUpOffered.add(playerId)")
                        > events.indexOf("FormSessionBridge.open"),
                "the one-off marker is set only after the form bridge opens a view");
        assertFalse(events.contains("\"Renunță\""),
                "the give-up form must not hardcode Romanian text");
        assertTrue(events.contains("FormSessionUseCase.Action.GIVE_UP"));
        assertTrue(events.contains("FormSessionBridge.cancelSession(playerId, sessionId)"));
        assertTrue(events.contains("giveUpOffered.remove(playerId)"));
        assertTrue(events.contains("clearGiveUpOffer(player)"));
        assertFalse(events.contains("application.service."));
        assertFalse(events.contains("adapter.out.persistence"));
    }

    @Test
    void giveUpPlayerFacingKeysExistInBothLocales() throws IOException {
        for (String locale : new String[] {"en_us", "ro_ro"}) {
            String language = Files.readString(LANG.resolve(locale + ".json"));
            for (String key : new String[] {
                    "straja.give_up.available",
                    "straja.give_up.title",
                    "straja.give_up.confirmation",
                    "straja.give_up.stale",
                    "straja.give_up.success"}) {
                assertTrue(language.contains("\"" + key + "\":"),
                        locale + " is missing " + key);
            }
        }

        String bridge = source("adapter/in/form/FormSessionBridge.java");
        String custody = source("application/service/CustodyService.java");
        assertTrue(bridge.contains("Component.translatable"));
        assertTrue(custody.contains("tellTranslationKey(\"straja.give_up.success\")"));
        assertFalse(custody.contains("Ai renunțat și ai murit"));
    }

    @Test
    void nativeScreenKeepsTheExistingSubmitCancelControls() throws IOException {
        String screen = source("adapter/out/client/StrajaFormScreen.java");
        assertTrue(screen.contains("Button.builder(Component.translatable(\"straja.form.submit\")"));
        assertTrue(screen.contains("Button.builder(Component.translatable(\"straja.form.cancel\")"));
        assertTrue(screen.contains("new FormPayloads.Submit"));
        assertTrue(screen.contains("new FormPayloads.Cancel"));
        assertFalse(screen.contains("class GiveUp"));
    }
}
