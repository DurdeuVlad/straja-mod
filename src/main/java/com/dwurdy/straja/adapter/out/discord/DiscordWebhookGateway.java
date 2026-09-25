package com.dwurdy.straja.adapter.out.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Outbound-only Discord adapter. The application only receives a safe payload;
 * no business service receives the webhook URL or raw free text.
 */
public final class DiscordWebhookGateway implements com.dwurdy.straja.application.port.out.DiscordWebhookGateway {
    @FunctionalInterface
    public interface Transport { boolean post(String webhookUrl, String payload); }

    private final String webhookUrl;
    private final Transport transport;

    public DiscordWebhookGateway(String webhookUrl, Transport transport) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl;
        this.transport = transport;
    }

    /** Production transport. Callers should use it from the outbox worker. */
    public DiscordWebhookGateway(String webhookUrl) {
        this(webhookUrl, DiscordWebhookGateway::postHttp);
    }

    @Override
    public boolean send(String safePayload) {
        if (webhookUrl.isBlank() || transport == null || safePayload == null) return false;
        return transport.post(webhookUrl, safePayload);
    }

    private static boolean postHttp(String webhookUrl, String safePayload) {
        try {
            String content = "{\"content\":\"Straja event: "
                    + escape(safePayload) + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(content))
                    .build();
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
