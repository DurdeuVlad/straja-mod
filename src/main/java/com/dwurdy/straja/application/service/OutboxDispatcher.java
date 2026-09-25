package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.DiscordWebhookGateway;
import com.dwurdy.straja.application.port.out.ServerExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded asynchronous sender for the persistent outbox. The game thread
 * only schedules work; HTTP runs on this worker while SavedData state changes
 * are marshalled back onto the server executor.
 */
public final class OutboxDispatcher implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "straja-outbox");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public void dispatch(OutboxService outbox, DiscordWebhookGateway gateway, int maxEvents,
                         ServerExecutor serverExecutor) {
        if (outbox == null || gateway == null || serverExecutor == null || maxEvents <= 0
                || !scheduled.compareAndSet(false, true)) return;
        try {
            serverExecutor.execute(() -> {
                try {
                    for (OutboxService.DeliveryClaim claim : outbox.claimDue(maxEvents)) {
                        executor.execute(() -> deliver(outbox, gateway, serverExecutor, claim));
                    }
                } finally {
                    scheduled.set(false);
                }
            });
        } catch (RuntimeException failure) {
            scheduled.set(false);
            throw failure;
        }
    }

    private void deliver(OutboxService outbox, DiscordWebhookGateway gateway,
                         ServerExecutor serverExecutor, OutboxService.DeliveryClaim claim) {
        boolean delivered = false;
        String error = "";
        try {
            delivered = gateway.send(claim.safePayload());
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName();
        }
        boolean result = delivered;
        String failure = error;
        serverExecutor.execute(() -> outbox.completeDelivery(claim, result, failure));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
