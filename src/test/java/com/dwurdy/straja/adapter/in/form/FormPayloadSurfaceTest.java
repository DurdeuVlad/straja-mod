package com.dwurdy.straja.adapter.in.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level contract for the serverbound form payload adapter: NeoForge's
 * registrar wraps playToServer handlers in a main-thread handler by default,
 * so handling must not opt back onto the network thread, must reject
 * non-player senders, and must never log submitted text.
 */
class FormPayloadSurfaceTest {
    private static final Path SOURCE =
            Path.of("src/main/java/com/dwurdy/straja/adapter/in/form/FormPayloads.java");

    private static String source() throws IOException {
        return Files.readString(SOURCE);
    }

    @Test
    void payloadsStayOnTheServerMainThread() throws IOException {
        String src = source();
        assertTrue(src.contains("playToServer"),
                "serverbound payloads must be registered through the registrar");
        assertFalse(src.contains("HandlerThread.NETWORK"),
                "handlers must keep the registrar's MAIN-thread default");
        assertFalse(src.contains("executesOn"),
                "handlers must not override the main-thread default");
    }

    @Test
    void handlersRejectNonPlayerSenders() throws IOException {
        String src = source();
        int handlers = 0;
        for (String line : src.split("\n")) {
            if (line.contains("instanceof ServerPlayer")) handlers++;
        }
        assertTrue(handlers >= 2,
                "submit and cancel must both require an authenticated ServerPlayer");
    }

    @Test
    void submittedTextIsNeverLoggedOrEchoed() throws IOException {
        String src = source();
        assertFalse(src.contains("LOGGER"), "payload text must not reach the log");
        assertFalse(src.contains("System.out"), "payload text must not be printed");
        assertFalse(src.contains("System.err"), "payload text must not be printed");
    }
}
