package com.dwurdy.straja.gametest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watchdog for a vanilla GameTest-runner race that can stall a whole CI job.
 *
 * <p>{@link GameTestInfo#tick} only counts the test's timeout after the
 * structure's chunks report entity-ticking:
 *
 * <pre>{@code
 * if (this.chunksLoaded || boundingBox.intersectingChunks()
 *         .allMatch(pos -> level.isPositionEntityTicking(pos.getWorldPosition()))) {
 *     this.chunksLoaded = true;
 *     ...
 *     this.tickInternal();   // <-- the 200-tick timeout lives behind this gate
 * }
 * }</pre>
 *
 * {@code isPositionEntityTicking} is the AND of two independently-updated
 * views: the FORCED ticket level ({@code inEntityTickingRange}, synchronous)
 * and the entity-section visibility ({@code entityManager.canPositionTick},
 * driven by an async {@code onFullChunkStatusChange} notification queued onto
 * the main thread when the chunk finishes promoting). During the batch spawn
 * storm — forty structures force-loaded back-to-back inside one
 * {@code runBatch} call — that async notification can be lost or superseded
 * (the 1.21.1 {@code pendingFullStateConfirmation} cancel/replace pattern),
 * leaving the ticket at entity-ticking level while the visibility map stays
 * HIDDEN — or the FORCED ticket's propagated level missing while the
 * visibility map is ready ({@code -ticket} verdict, also seen locally). The
 * gate then returns false <em>forever</em>: the test never starts, the
 * timeout never counts, and the batch ticks until the CI job is cancelled.
 * Observed on CI — the leading grid row of eight tests stuck for 11+
 * minutes; the step's earlier stall showed the same frozen signature.
 *
 * <p>This watchdog re-fires the end-state of both halves: after the batch
 * starts it periodically re-asserts the FORCED ticket via the public
 * {@code ServerChunkCache.updateChunkForced} (idempotent when already
 * present) and calls {@link PersistentEntitySectionManager#updateChunkStatus}
 * with {@link FullChunkStatus#ENTITY_TICKING} — the same value the lost
 * notification carries — for every force-loaded chunk plus every chunk an
 * unstarted test's gate checks. While any tracked test remains unstarted it
 * also logs which half failed per test, so a residual stall is diagnosable
 * from CI logs.
 *
 * <p>Scheduling uses {@link ServerTickEvent.Post} rather than
 * {@code server.tell(new TickTask(...))}: {@code MinecraftServer.shouldRun}
 * executes queued TickTasks eagerly whenever the tick budget has spare time,
 * so TickTask delays are advisory and a queue of them bursts in one tick.
 *
 * <p>{@code entityManager} and {@code GameTestTicker.testInfos} are private
 * in vanilla with no public accessor for what we need, so they are reached
 * reflectively — gameTest sourceset only, dev-runtime Mojang-mapped names,
 * never shipped. If reflection fails the watchdog degrades to public-API
 * ticket re-asserts and stops early-exit diagnosis.
 */
@GameTestHolder("straja")
public final class GameTestChunkWatchdog {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameTestChunkWatchdog.class);

    /** Ticks after batch start before the first nudge (normal start is <10). */
    private static final int FIRST_NUDGE_DELAY = 30;
    /** Ticks between nudges. */
    private static final int NUDGE_INTERVAL = 40;
    /** Cap on nudges per batch — ~36s of coverage after batch start. */
    private static final int MAX_NUDGES = 45;

    private static Field entityManagerField;
    private static Field tickerTestInfosField;

    private static volatile boolean tickerRegistered;
    private static volatile ServerLevel armedLevel;
    private static volatile int ticksUntilNudge;
    private static volatile int nudgesRemaining;

    private GameTestChunkWatchdog() {}

    /**
     * Runs inside {@code GameTestRunner.runBatch} after the batch's
     * structures are spawned and force-loaded, before they are handed to the
     * ticker — the exact window in which the chunk-readiness race can strike.
     * {@code @BeforeBatch} binds to one batch name, so each batch in this
     * suite gets its own annotated entry point into the same arming logic.
     */
    @BeforeBatch(batch = "defaultBatch")
    public static void armDefaultBatchWatchdog(ServerLevel level) {
        arm(level);
    }

    /** Same arming for the isolated AT10 batch (see LawAcceptanceGameTests). */
    @BeforeBatch(batch = "debtAt10")
    public static void armDebtAt10BatchWatchdog(ServerLevel level) {
        arm(level);
    }

    private static void arm(ServerLevel level) {
        ensureTickerRegistered();
        armedLevel = level;
        ticksUntilNudge = FIRST_NUDGE_DELAY;
        nudgesRemaining = MAX_NUDGES;
    }

    private static void ensureTickerRegistered() {
        if (!tickerRegistered) {
            synchronized (GameTestChunkWatchdog.class) {
                if (!tickerRegistered) {
                    NeoForge.EVENT_BUS.addListener(GameTestChunkWatchdog::onServerTick);
                    tickerRegistered = true;
                }
            }
        }
    }

    /**
     * One real server tick per invocation — counts down, then nudges.
     * Disarms when the batch's tests have all started, when the ticker is
     * empty (batch ended), or when the nudge budget is spent.
     */
    private static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = armedLevel;
        if (level == null || event.getServer() != level.getServer()) {
            return;
        }
        if (--ticksUntilNudge > 0) {
            return;
        }
        if (shouldDisarm() || nudgesRemaining <= 0) {
            armedLevel = null;
            return;
        }
        nudgesRemaining--;
        ticksUntilNudge = NUDGE_INTERVAL;
        int nudge = MAX_NUDGES - nudgesRemaining;
        try {
            reassertStructureChunks(level);
            diagnoseUnstarted(level, nudge);
        } catch (Throwable nudgeFailure) {
            // A watchdog must never take the server (or the batch) down.
            LOGGER.warn("[GameTestChunkWatchdog] nudge {} failed", nudge, nudgeFailure);
        }
    }

    /**
     * True when every test currently handed to the ticker has entered
     * {@code tickInternal} at least once, or the ticker was cleared (batch
     * ended). False when reflection is unavailable — the ticket re-assert is
     * still a valid unstick path on its own, so keep nudging.
     */
    private static boolean shouldDisarm() {
        Collection<GameTestInfo> infos = trackedTestInfos();
        if (infos == null) {
            return false;
        }
        if (infos.isEmpty()) {
            return true;
        }
        return infos.stream().allMatch(GameTestInfo::hasStarted);
    }

    /**
     * Re-fires the two half-states of {@code isPositionEntityTicking} for
     * every chunk the batch force-loaded <em>plus</em> every chunk an
     * unstarted test's gate actually checks — the two bboxes should agree,
     * but covering the gate directly removes any dependence on that
     * assumption.
     */
    private static void reassertStructureChunks(ServerLevel level) {
        PersistentEntitySectionManager<Entity> entityManager = entityManager(level);
        level.getForcedChunks().forEach(
                packed -> reassertChunk(level, entityManager, new ChunkPos(packed)));
        Collection<GameTestInfo> infos = trackedTestInfos();
        if (infos != null) {
            infos.stream().filter(info -> !info.hasStarted()).forEach(info -> {
                try {
                    StructureUtils.getStructureBoundingBox(info.getStructureBlockEntity())
                            .intersectingChunks()
                            .forEach(chunkPos -> reassertChunk(level, entityManager, chunkPos));
                } catch (RuntimeException badInfo) {
                    LOGGER.debug("[GameTestChunkWatchdog] skipped {} — {}",
                            safeTestName(info), badInfo.toString());
                }
            });
        }
    }

    private static void reassertChunk(ServerLevel level,
                                      PersistentEntitySectionManager<Entity> entityManager,
                                      ChunkPos chunkPos) {
        // Ticket half — public, idempotent when already present.
        level.getChunkSource().updateChunkForced(chunkPos, true);
        // Visibility half — what the queued onFullChunkStatusChange sets.
        if (entityManager != null) {
            entityManager.updateChunkStatus(chunkPos, FullChunkStatus.ENTITY_TICKING);
        }
    }

    /**
     * One summary line per nudge while tests stay unstarted: names tests
     * whose chunks still fail the entity-ticking gate and which half failed
     * ({@code -visibility} = lost section update, {@code -ticket} = dropped
     * FORCED ticket). Tests whose chunks tick are just inside the placement
     * countdown and are counted, not listed.
     */
    private static void diagnoseUnstarted(ServerLevel level, int nudge) {
        Collection<GameTestInfo> infos = trackedTestInfos();
        if (infos == null) {
            return;
        }
        List<String> stuck = new ArrayList<>();
        int warming = 0;
        for (GameTestInfo info : infos) {
            if (info.hasStarted()) {
                continue;
            }
            StringBuilder verdict = new StringBuilder();
            boolean[] ticking = {true};
            try {
                StructureUtils.getStructureBoundingBox(info.getStructureBlockEntity())
                        .intersectingChunks()
                        .forEach(chunkPos -> {
                            BlockPos pos = chunkPos.getWorldPosition();
                            boolean entities = level.isNaturalSpawningAllowed(pos);
                            boolean ticket = level.getChunkSource().chunkMap
                                    .getDistanceManager()
                                    .inEntityTickingRange(chunkPos.toLong());
                            ticking[0] &= entities && ticket;
                            verdict.append(' ').append(chunkPos).append(
                                    entities && ticket ? "+" :
                                    entities ? "-ticket" : ticket ? "-visibility" : "-both");
                        });
            } catch (RuntimeException badInfo) {
                verdict.append(" <unreadable ").append(badInfo).append('>');
                ticking[0] = false;
            }
            if (ticking[0]) {
                warming++;
            } else {
                stuck.add(info.getTestName() + ":" + verdict);
            }
        }
        if (!stuck.isEmpty()) {
            LOGGER.warn("[GameTestChunkWatchdog] nudge {} — {} unstarted "
                    + "({} blocked on chunks, {} warming up) — {}",
                    nudge, stuck.size() + warming, stuck.size(), warming,
                    String.join(", ", stuck));
        } else if (warming > 0) {
            LOGGER.info("[GameTestChunkWatchdog] nudge {} — {} unstarted "
                    + "(all warming up, chunks ticking)", nudge, warming);
        }
    }

    private static String safeTestName(GameTestInfo info) {
        try {
            return info.getTestName();
        } catch (RuntimeException unnamed) {
            return "<unknown>";
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<GameTestInfo> trackedTestInfos() {
        try {
            if (tickerTestInfosField == null) {
                tickerTestInfosField = GameTestTicker.class.getDeclaredField("testInfos");
                tickerTestInfosField.setAccessible(true);
            }
            return (Collection<GameTestInfo>) tickerTestInfosField.get(GameTestTicker.SINGLETON);
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static PersistentEntitySectionManager<Entity> entityManager(ServerLevel level) {
        try {
            if (entityManagerField == null) {
                entityManagerField = ServerLevel.class.getDeclaredField("entityManager");
                entityManagerField.setAccessible(true);
            }
            return (PersistentEntitySectionManager<Entity>) entityManagerField.get(level);
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            LOGGER.warn("[GameTestChunkWatchdog] entityManager not reachable "
                    + "reflectively; ticket re-assert only", inaccessible);
            return null;
        }
    }
}
