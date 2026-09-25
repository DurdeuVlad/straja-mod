package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.DiscordWebhookGateway;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded asynchronous sender for the persistent outbox. The game thread
 * only schedules work; HTTP and retry state changes happen on this worker.
 */
public final class OutboxDispatcher implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "straja-outbox");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public void dispatch(OutboxService outbox, DiscordWebhookGateway gateway, int maxEvents) {
        if (outbox == null || gateway == null || maxEvents <= 0 || !scheduled.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                outbox.dispatchDue(gateway, maxEvents);
            } finally {
                scheduled.set(false);
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
