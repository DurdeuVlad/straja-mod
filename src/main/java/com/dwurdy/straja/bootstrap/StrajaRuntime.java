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
import com.dwurdy.straja.application.port.out.Clock;
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
    private final com.dwurdy.straja.application.service.NpcAdminService npcs;
    private final com.dwurdy.straja.application.service.MissionService missions;
    private final com.dwurdy.straja.application.service.CustodyService custody;
    private final com.dwurdy.straja.application.service.PrisonService prison;
    private final com.dwurdy.straja.application.service.FineService fines;
    private final com.dwurdy.straja.application.service.ComplaintService complaints;
    private final com.dwurdy.straja.application.service.ReportService reports;
    private final com.dwurdy.straja.application.service.AudienceService audiences;
    private final com.dwurdy.straja.application.service.EmergencyService emergency;
    private com.dwurdy.straja.application.service.AdminService admin;
    private final com.dwurdy.straja.application.service.RoomService rooms;
    private final com.dwurdy.straja.application.service.ArchiveService archive;
    private final com.dwurdy.straja.application.service.MigrationService migration;
    private final com.dwurdy.straja.application.service.FormSessionService formSessions;
    private final com.dwurdy.straja.application.service.PolicyService policyService;
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
        // The surface is local-only: test commands can never be enabled outside
        // local environments.
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
                return prefix + "-" + sequence.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
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
                new SavedStores.Reports(stores),
                new SavedStores.Audiences(stores),
                new SavedStores.Emergency(stores),
                new SavedStores.MissionTemplates(stores),
                new SavedStores.Custody(stores),
                new SavedStores.Archive(stores),
                new SavedStores.Npcs(stores),
                new SavedStores.Test(stores),
                new ItemCoinCurrencyProvider(() -> policies.coinItemIds),
                new EnvelopeDeliveryProvider(server, policies),
                new com.dwurdy.straja.adapter.out.minecraft.MinecraftWorldGateway(server),
                new com.dwurdy.straja.adapter.out.faction.ScoreboardFactionGateway(server));

        this.players = new PlayerService(ctx);
        this.audit = new AuditService(ctx);
        this.equipment = new EquipmentService(ctx);
        this.guards = new GuardService(ctx, players, audit, equipment, this::bootId);
        this.npcs = new com.dwurdy.straja.application.service.NpcAdminService(ctx);
        this.missions = new com.dwurdy.straja.application.service.MissionService(ctx, players, audit);
        this.custody = new com.dwurdy.straja.application.service.CustodyService(ctx, players, audit);
        this.prison = new com.dwurdy.straja.application.service.PrisonService(ctx, players, audit, custody);
        this.fines = new com.dwurdy.straja.application.service.FineService(ctx, players, audit, prison);
        this.complaints = new com.dwurdy.straja.application.service.ComplaintService(ctx, players, audit);
        this.reports = new com.dwurdy.straja.application.service.ReportService(ctx, players, audit);
        this.audiences = new com.dwurdy.straja.application.service.AudienceService(ctx, players, audit);
        this.emergency = new com.dwurdy.straja.application.service.EmergencyService(ctx, players, audit);
        this.rooms = new com.dwurdy.straja.application.service.RoomService(ctx, players, audit, ctx.world());
        this.archive = new com.dwurdy.straja.application.service.ArchiveService(ctx, players, audit);
        this.migration = new com.dwurdy.straja.application.service.MigrationService(ctx, audit);
        this.formSessions = new com.dwurdy.straja.application.service.FormSessionService(clock, ids);
        // Runtime policy overrides: TOML-resolved baseline + persisted YAML
        // layer applied to the live policies object before services run.
        StrajaPolicies baseline = StrajaServerConfig.toPolicies();
        this.policyService = new com.dwurdy.straja.application.service.PolicyService(
                ctx, players, audit, baseline,
                new com.dwurdy.straja.adapter.out.config.YamlPolicyOverrideStore(
                        net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("straja-policies.yaml")));
        for (String failed : policyService.applyPersistedOverrides()) {
            com.dwurdy.straja.StrajaMod.LOGGER.warn(
                    "[Straja] Ignored malformed policy override '{}' in straja-policies.yaml", failed);
        }
        this.guards.onStatusChange((p, reason) -> this.missions.cancelOpenFor(p, reason));
        this.guards.onStatusChange((p, reason) -> this.rooms.releaseFor(p));
        this.guards.onPromotedToGuard(p -> this.rooms.assignAutomatically(p));
        this.admin = new com.dwurdy.straja.application.service.AdminService(
                ctx, players, guards, policyService, emergency);
    }

    public static synchronized StrajaRuntime start(MinecraftServer server) {
        instance = new StrajaRuntime(server);
        com.dwurdy.straja.adapter.in.form.FormSessionBridge.install(instance.formSessions);
        com.dwurdy.straja.adapter.in.form.FormPayloads.setSubmissionConsumer(
                new com.dwurdy.straja.adapter.in.form.FormSubmissionRouter(
                        instance.guards, instance.missions, instance.complaints, instance.fines,
                        instance.archive, instance.reports, instance.audiences, instance.admin)::submit);
        instance.logDeploymentGates();
        instance.custody.recoverOnRestart();
        return instance;
    }

    /**
     * Deployment gates: outside local, misconfiguration that weakens identity
     * or tooling must be impossible to miss. The identity gates are also
     * enforced at the decision points (see PlayerService.isCommissioner and
     * DebugCommands.debugAllowed); this log makes residual failures visible.
     */
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
        com.dwurdy.straja.adapter.in.form.FormSessionBridge.clear();
        com.dwurdy.straja.adapter.in.form.FormPayloads.setSubmissionConsumer(null);
        instance = null;
    }

    public static StrajaRuntime get() {
        return instance;
    }

    public MinecraftServer server() { return server; }
    public StrajaPolicies policies() { return policies; }
    public StrajaContext context() { return ctx; }
    public MinecraftServerGateway serverGateway() { return serverGateway; }
    public PlayerService players() { return players; }
    public AuditService audit() { return audit; }
    public EquipmentService equipment() { return equipment; }
    public GuardService guards() { return guards; }
    public com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase guardRecruitment() { return guards; }
    public com.dwurdy.straja.application.port.in.GuardDutyUseCase guardDuty() { return guards; }
    public com.dwurdy.straja.application.port.in.MissionRoleplayUseCase missionRoleplay() { return missions; }
    public com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase complaintRoleplay() { return complaints; }
    public com.dwurdy.straja.application.port.in.ReportUseCase reportRoleplay() { return reports; }
    public com.dwurdy.straja.application.port.in.AudienceUseCase audienceRoleplay() { return audiences; }
    public com.dwurdy.straja.application.port.in.EmergencyUseCase emergencyRoleplay() { return emergency; }
    public com.dwurdy.straja.application.port.in.AdminRoleplayUseCase adminRoleplay() { return admin; }
    public com.dwurdy.straja.application.port.in.FineRoleplayUseCase fineRoleplay() { return fines; }
    public com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase custodyRoleplay() { return custody; }
    public com.dwurdy.straja.application.port.in.PrisonRoleplayUseCase prisonRoleplay() { return prison; }
    public com.dwurdy.straja.application.port.in.RoomRoleplayUseCase roomRoleplay() { return rooms; }
    public com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase archiveRoleplay() { return archive; }
    public com.dwurdy.straja.application.port.in.PlayerQueryUseCase playerQueries() { return players; }
    public com.dwurdy.straja.application.port.in.NpcRegistryUseCase npcRegistry() { return npcs; }
    public com.dwurdy.straja.application.service.NpcAdminService npcs() { return npcs; }
    public com.dwurdy.straja.application.service.MissionService missions() { return missions; }
    public com.dwurdy.straja.application.service.CustodyService custody() { return custody; }
    public com.dwurdy.straja.application.service.PrisonService prison() { return prison; }
    public com.dwurdy.straja.application.service.FineService fines() { return fines; }
    public com.dwurdy.straja.application.service.ComplaintService complaints() { return complaints; }
    public com.dwurdy.straja.application.service.RoomService rooms() { return rooms; }
    public com.dwurdy.straja.application.service.ArchiveService archive() { return archive; }
    public com.dwurdy.straja.application.service.MigrationService migration() { return migration; }
    public com.dwurdy.straja.application.port.in.FormSessionUseCase formSessions() { return formSessions; }
    public com.dwurdy.straja.application.port.in.PolicyConfigUseCase policyConfig() { return policyService; }
    public com.dwurdy.straja.application.port.out.MutableClock clock() { return clock; }
    public com.dwurdy.straja.adapter.in.test.TestPlayerRegistry testPlayers() { return testPlayers; }
    public String bootId() { return bootId; }
}
