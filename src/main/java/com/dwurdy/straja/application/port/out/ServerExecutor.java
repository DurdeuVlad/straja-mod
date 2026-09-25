package com.dwurdy.straja.application.port.out;

/**
 * Schedules authoritative SavedData/domain mutations on the Minecraft server
 * thread. Slow external I/O must never use this executor directly.
 */
@FunctionalInterface
public interface ServerExecutor {
    void execute(Runnable task);
}
