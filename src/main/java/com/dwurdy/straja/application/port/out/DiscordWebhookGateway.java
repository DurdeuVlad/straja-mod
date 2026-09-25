package com.dwurdy.straja.application.port.out;

/** Outbound-only transport. Business services must never call HTTP directly. */
public interface DiscordWebhookGateway {
    boolean send(String safePayload);
}
