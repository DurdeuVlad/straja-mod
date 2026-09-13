package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.adapter.out.delivery.EnvelopeDeliveryProvider;
import com.dwurdy.straja.adapter.out.economy.ItemCoinCurrencyProvider;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftServerGateway;
import com.dwurdy.straja.adapter.out.persistence.NbtStore;
import com.dwurdy.straja.adapter.out.persistence.SavedPlayerStateRepository;
import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.service.AuditService;
import com.dwurdy.straja.application.service.EquipmentService;
import com.dwurdy.straja.application.service.GuardService;
import com.dwurdy.straja.application.service.PlayerService;
import com.dwurdy.straja.config.StrajaServerConfig;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;

/**
 * Composition root: assembles policies + outbound adapters into the
 * application context and the application services. Rebuilt per server start.
 */
public final class StrajaRuntime {
    private static volatile StrajaRuntime instance;

    private final MinecraftServer server;
    private final StrajaPolicies policies;
    private final StrajaContext ctx;
    private final MinecraftServerGateway serverGateway;
    private final PlayerService players;
    private final AuditService audit;
    private final EquipmentService equipment;
    private final GuardService guards;
    private final com.dwurdy.straja.application.service.PersonnelWorkflowService personnel;
    private final com.dwurdy.straja.application.service.DutySessionService dutySessions;
    private final com.dwurdy.straja.adapter.out.minecraft.DutyTeamAdapter dutyTeams;
    private final com.dwurdy.straja.application.service.NpcAdminService npcs;
    private final com.dwurdy.straja.application.service.MissionService missions;
    private final com.dwurdy.straja.application.service.CustodyService custody;
    private final com.dwurdy.straja.application.service.PrisonService prison;
    private final com.dwurdy.straja.application.service.FineService fines;
    private final com.dwurdy.straja.application.service.ComplaintService complaints;
    private final com.dwurdy.straja.application.service.RoomService rooms;
    private final com.dwurdy.straja.application.service.ArchiveService archive;
    private final com.dwurdy.straja.application.service.MigrationService migration;
    private final com.dwurdy.straja.application.port.out.MutableClock clock;
    private final com.dwurdy.straja.adapter.in.test.TestPlayerRegistry testPlayers =
            new com.dwurdy.straja.adapter.in.test.TestPlayerRegistry();
    private final String bootId = UUID.randomUUID().toString();

    private StrajaRuntime(MinecraftServer server) {
        this.server = server;
        this.policies = StrajaServerConfig.toPolicies();
        this.serverGateway = new MinecraftServerGateway(server);
        StoreAccess stores = name -> new NbtStore(StrajaDataProvider.get(server, name));

        this.clock = new com.dwurdy.straja.application.port.out.MutableClock();

        // In test mode the server view also sees virtual players so console
        // scenarios exercise the same findPlayer/notify paths as real players.
        boolean testSurface = policies.testCommandsEnabled && policies.isLocalEnvironment();
        com.dwurdy.straja.application.port.out.ServerGateway serverView = testSurface
                ? new com.dwurdy.straja.application.port.out.ServerGateway() {
                    @Override public java.util.List<com.dwurdy.straja.application.port.out.PlayerGateway> onlinePlayers() {
                        var list = new java.util.ArrayList<>(serverGateway.onlinePlayers());
                        list.addAll(testPlayers.all());
                        return list;
                    }
                    @Override public com.dwurdy.straja.application.port.out.PlayerGateway findPlayer(String nameOrUuid) {
                        var real = serverGateway.findPlayer(nameOrUuid);
                        if (real != null) return real;
                        var virtual = testPlayers.get(nameOrUuid);
                        if (virtual == null) {
                            try { virtual = testPlayers.byUuid(java.util.UUID.fromString(nameOrUuid)); }
                            catch (IllegalArgumentException ignored) {}
                        }
                        return virtual != null && virtual.isOnline() ? virtual : null;
                    }
                    @Override public long tickCount() { return serverGateway.tickCount(); }
                }
                : serverGateway;

        AtomicLong sequence = new AtomicLong();
        IdGenerator ids = new IdGenerator() {
            @Override public String newId(String prefix) {
                return prefix + "-" + sequence.incrementAndGet() + "-"
                        + UUID.randomUUID().toString().substring(0, 8);
            }
            @Override public String token() {
                return UUID.randomUUID().toString();
            }
        };

        this.ctx = new StrajaContext(
                policies,
                clock,
                ids,
                serverView,
                new SavedPlayerStateRepository(stores),
                new SavedStores.Setup(stores),
                new SavedStores.Audit(stores, policies.auditRetentionLimit),
                new SavedStores.Inbox(stores),
                new SavedStores.Missions(stores),
                new SavedStores.Fines(stores),
                new SavedStores.Prison(stores),
                new SavedStores.Rooms(stores),
                new SavedStores.Complaints(stores),
                new SavedStores.Custody(stores),
                new SavedStores.Archive(stores),
                new SavedStores.Npcs(stores),
                new SavedStores.Test(stores),
                new ItemCoinCurrencyProvider(policies.coinItemIds),
                new EnvelopeDeliveryProvider(server, policies),
                new com.dwurdy.straja.adapter.out.minecraft.MinecraftWorldGateway(server));

        this.players = new PlayerService(ctx);
        this.audit = new AuditService(ctx);
        this.equipment = new EquipmentService(ctx);
        this.guards = new GuardService(ctx, players, audit, equipment);
        this.personnel = new com.dwurdy.straja.application.service.PersonnelWorkflowService(
                ctx, players, guards, audit);
        this.dutySessions = new com.dwurdy.straja.application.service.DutySessionService(
                ctx, players, guards, equipment);
        this.dutyTeams = new com.dwurdy.straja.adapter.out.minecraft.DutyTeamAdapter(server);
        this.npcs = new com.dwurdy.straja.application.service.NpcAdminService(ctx);
        this.missions = new com.dwurdy.straja.application.service.MissionService(ctx, players, audit);
        this.custody = new com.dwurdy.straja.application.service.CustodyService(ctx, players, audit);
        this.prison = new com.dwurdy.straja.application.service.PrisonService(ctx, players, audit, custody);
        this.fines = new com.dwurdy.straja.application.service.FineService(ctx, players, audit, prison);
        this.complaints = new com.dwurdy.straja.application.service.ComplaintService(ctx, players, audit);
        this.rooms = new com.dwurdy.straja.application.service.RoomService(ctx, players, audit, ctx.world());
        this.archive = new com.dwurdy.straja.application.service.ArchiveService(ctx, players, audit);
        this.migration = new com.dwurdy.straja.application.service.MigrationService(ctx, audit);
        this.guards.onStatusChange((p, reason) -> this.missions.cancelOpenFor(p, reason));
        this.guards.onStatusChange((p, reason) -> this.rooms.releaseFor(p));
        this.guards.onPromotedToGuard(p -> this.rooms.assignAutomatically(p));
    }

    public static synchronized StrajaRuntime start(MinecraftServer server) {
        instance = new StrajaRuntime(server);
        instance.logDeploymentGates();
        return instance;
    }

    private void logDeploymentGates() {
        if (policies.isLocalEnvironment()) return;
        if (policies.requireCommissionerUuidOutsideLocal && policies.commissionerUuid.isEmpty()) {
            com.dwurdy.straja.StrajaMod.LOGGER.error(
                    "[Straja] DEPLOYMENT GATE FAILED: commissionerUuid is not pinned outside a local "
                            + "environment — no commissioner identity will be granted");
        }
        if (policies.requireDebugDisabledOutsideLocal && policies.debugEnabled) {
            com.dwurdy.straja.StrajaMod.LOGGER.error(
                    "[Straja] DEPLOYMENT GATE FAILED: debug.enabled=true outside a local environment "
                            + "— debug commands are disabled until this is corrected");
        }
        if (policies.requireRealCoinProviderOutsideLocal && !ctx.currency().available()) {
            com.dwurdy.straja.StrajaMod.LOGGER.error(
                    "[Straja] DEPLOYMENT GATE FAILED: coin provider '{}' is unavailable outside a "
                            + "local environment — deposits/withdrawals will fail closed",
                    ctx.currency().name());
        }
    }

    public static synchronized void stop() {
        instance = null;
    }

    public static StrajaRuntime get() { return instance; }

    public MinecraftServer server() { return server; }
    public StrajaPolicies policies() { return policies; }
    public StrajaContext context() { return ctx; }
    public MinecraftServerGateway serverGateway() { return serverGateway; }
    public PlayerService players() { return players; }
    public AuditService audit() { return audit; }
    public EquipmentService equipment() { return equipment; }
    public GuardService guards() { return guards; }
    public com.dwurdy.straja.application.service.PersonnelWorkflowService personnel() { return personnel; }
    public com.dwurdy.straja.application.service.DutySessionService dutySessions() { return dutySessions; }
    public com.dwurdy.straja.adapter.out.minecraft.DutyTeamAdapter dutyTeams() { return dutyTeams; }
    public com.dwurdy.straja.application.service.NpcAdminService npcs() { return npcs; }
    public com.dwurdy.straja.application.service.MissionService missions() { return missions; }
    public com.dwurdy.straja.application.service.CustodyService custody() { return custody; }
    public com.dwurdy.straja.application.service.PrisonService prison() { return prison; }
    public com.dwurdy.straja.application.service.FineService fines() { return fines; }
    public com.dwurdy.straja.application.service.ComplaintService complaints() { return complaints; }
    public com.dwurdy.straja.application.service.RoomService rooms() { return rooms; }
    public com.dwurdy.straja.application.service.ArchiveService archive() { return archive; }
    public com.dwurdy.straja.application.service.MigrationService migration() { return migration; }
    public com.dwurdy.straja.application.port.out.MutableClock clock() { return clock; }
    public com.dwurdy.straja.adapter.in.test.TestPlayerRegistry testPlayers() { return testPlayers; }
    public String bootId() { return bootId; }
}
