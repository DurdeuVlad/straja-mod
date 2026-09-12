package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ItemSpec;

/**
 * Physical mail delivery. Implemented by Envelope. A delivery never reports
 * false success: failures return a receipt with mode FAILED so the caller can
 * persist a retry state.
 */
public interface DeliveryProvider {
    boolean available();

    /** Sends a letter to an online or offline player via the mail system. */
    Outcome sendLetter(PlayerGateway sender, String recipientName, String subject, String body);

    /** Sends a sealed package containing the given items to a player. */
    Outcome sendPackage(PlayerGateway sender, String recipientName, java.util.List<ItemSpec> contents, String label);

    enum Mode { DELIVERED, PENDING_MAILBOX, CHAT_FALLBACK, DISABLED, UNAVAILABLE, FAILED }

    record Outcome(Mode mode, String detail) {
        public boolean succeeded() {
            return mode == Mode.DELIVERED || mode == Mode.PENDING_MAILBOX || mode == Mode.CHAT_FALLBACK;
        }

        public static Outcome delivered() { return new Outcome(Mode.DELIVERED, ""); }
        public static Outcome failed(String detail) { return new Outcome(Mode.FAILED, detail); }
    }
}
