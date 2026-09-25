package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.adapter.out.delivery.EnvelopeDeliveryProvider;
import com.dwurdy.straja.adapter.out.economy.ItemCoinCurrencyProvider;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftServerGateway;
import com.dwurdy.straja.adapter.out.persistence.NbtStore;
import com.dwurdy.straja.adapter.out.persistence.SavedPlayerStateRepository;
import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider;
import com.dwurdy.straja.adapter.in.compat.OptionalModCompatibility;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.PlayerGateway;
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
    private final OptionalModCompatibility.Profile compatibility;
    private final StrajaContext ctx;
    private final MinecraftServerGateway serverGateway;
    private final PlayerService players;
    private final AuditService audit;
    private final EquipmentService equipment;
    private final GuardService guards;
    private final com.dwurdy.straja.application.service.ArmoryService armory;
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
    private com.dwurdy.straja.application.service.AdminToolService adminTools;
    private final com.dwurdy.straja.application.service.RoomService rooms;
    private final com.dwurdy.straja.application.service.ArchiveService archive;
    private final com.dwurdy.straja.application.service.IdentityCardService identityCards;
    private final com.dwurdy.straja.application.service.SecretaryService secretary;
    private final com.dwurdy.straja.application.service.MigrationService migration;
    private final com.dwurdy.straja.application.service.FormSessionService formSessions;
    private volatile com.dwurdy.straja.adapter.in.form.FormSubmissionRouter npcFormRouter;
    private final com.dwurdy.straja.application.service.PolicyService policyService;
    private final com.dwurdy.straja.application.service.IncidentService incidents;
    private final com.dwurdy.straja.application.service.BoloService bolos;
    private final com.dwurdy.straja.application.service.EvidenceService evidence;
    private final com.dwurdy.straja.application.service.ArrestRecordService arrestRecords;
    private final com.dwurdy.straja.application.service.ReputationService reputation;
    private final com.dwurdy.straja.application.service.RpExpansionService expansion;
    private final com.dwurdy.straja.application.service.OperationRecoveryService v2Operations;
    private final com.dwurdy.straja.application.service.PersonnelService v2Personnel;
    private final com.dwurdy.straja.application.service.AuthorizationService v2Authorization;
    private final com.dwurdy.straja.application.service.PromotionService v2Promotions;
    private final com.dwurdy.straja.application.service.StationService v2Stations;
    private final com.dwurdy.straja.application.service.DocumentService v2Documents;
    private final com.dwurdy.straja.application.service.EquipmentLedgerService v2EquipmentLedger;
    private final com.dwurdy.straja.application.service.MobilizationService v2Mobilizations;
    private final com.dwurdy.straja.application.service.CampaignService v2Campaigns;
    private final com.dwurdy.straja.application.service.SettlementService v2Settlements;
    private final com.dwurdy.straja.application.service.MissionV2Service v2Missions;
    private final com.dwurdy.straja.application.service.MissionGeneratorService v2Generators;
    private final com.dwurdy.straja.application.service.OutboxService v2Outbox;
    private final com.dwurdy.straja.application.service.OutboxDispatcher v2OutboxDispatcher;
    private final com.dwurdy.straja.application.port.out.DiscordWebhookGateway v2DiscordGateway;
    private final com.dwurdy.straja.application.service.ComplaintEscalationService v2ComplaintEscalation;
    private final com.dwurdy.straja.application.service.ConsistencyService v2Consistency;
    private final com.dwurdy.straja.application.port.out.MutableClock clock;
    private final com.dwurdy.straja.adapter.in.test.TestPlayerRegistry testPlayers =
            new com.dwurdy.straja.adapter.in.test.TestPlayerRegistry();
    private final String bootId = UUID.randomUUID().toString();

    private StrajaRuntime(MinecraftServer server) {
        this.server = server;
        this.policies = StrajaServerConfig.toPolicies();
        this.compatibility = OptionalModCompatibility.detect();
        OptionalModCompatibility.applyFailSafe(compatibility, policies);
        OptionalModCompatibility.log(compatibility);
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
                new com.dwurdy.straja.adapter.out.faction.ScoreboardFactionGateway(server),
                new SavedStores.AdminTools(stores),
                new SavedStores.IdentityCards(stores),
                new SavedStores.Incidents(stores),
                new SavedStores.Bolos(stores),
                new SavedStores.Evidence(stores),
                new SavedStores.ArrestRecords(stores),
                new SavedStores.Reputation(stores));

        var personnelRepository = new SavedStores.Personnel(stores);
        var promotionRepository = new SavedStores.Promotions(stores);
        var stationRepository = new SavedStores.Stations(stores);
        var documentRepository = new SavedStores.Documents(stores);
        var equipmentRepository = new SavedStores.EquipmentLedger(stores);
        var mobilizationRepository = new SavedStores.Mobilizations(stores);
        var campaignRepository = new SavedStores.Campaigns(stores);
        var settlementRepository = new SavedStores.Settlements(stores);
        var operationRepository = new SavedStores.Operations(stores);
        var outboxRepository = new SavedStores.Outbox(stores);
        this.v2Operations = new com.dwurdy.straja.application.service.OperationRecoveryService(
                operationRepository, clock, ids);
        this.v2Personnel = new com.dwurdy.straja.application.service.PersonnelService(
                personnelRepository, clock, ids);
        this.v2Authorization = new com.dwurdy.straja.application.service.AuthorizationService(
                personnelRepository, stationRepository, mobilizationRepository, clock);
        this.v2Personnel.useAuthorization(this.v2Authorization);
        this.v2Promotions = new com.dwurdy.straja.application.service.PromotionService(
                promotionRepository, personnelRepository, v2Authorization, clock, ids);
        this.v2Stations = new com.dwurdy.straja.application.service.StationService(
                stationRepository, clock, ids);
        this.v2Personnel.useStationService(v2Stations);
        this.v2Documents = new com.dwurdy.straja.application.service.DocumentService(
                documentRepository, clock, ids, v2Authorization, v2Operations);
        this.v2Personnel.useDocumentService(v2Documents);
        this.v2EquipmentLedger = new com.dwurdy.straja.application.service.EquipmentLedgerService(
                equipmentRepository, clock, ids, v2Authorization);
        this.v2EquipmentLedger.useDocumentService(v2Documents);
        this.v2EquipmentLedger.useStationService(v2Stations);
        this.v2Mobilizations = new com.dwurdy.straja.application.service.MobilizationService(
                mobilizationRepository, personnelRepository, v2Authorization, clock, ids);
        this.v2Campaigns = new com.dwurdy.straja.application.service.CampaignService(
                campaignRepository, clock, ids, v2Authorization);
        this.v2Settlements = new com.dwurdy.straja.application.service.SettlementService(
                settlementRepository, clock, ids, this.ctx.currency());
        this.v2Settlements.reconcile();
        this.v2Mobilizations.useSettlementService(v2Settlements, policies.mobilizationPay);
        this.v2Missions = new com.dwurdy.straja.application.service.MissionV2Service(
                this.ctx.missions(), v2Authorization, v2Settlements, clock, ids);
        this.v2Missions.useCampaignService(v2Campaigns);
        this.v2Generators = new com.dwurdy.straja.application.service.MissionGeneratorService();
        this.v2Generators.registerDefaults();
        this.v2Outbox = new com.dwurdy.straja.application.service.OutboxService(
                outboxRepository, clock, ids);
        this.v2OutboxDispatcher = new com.dwurdy.straja.application.service.OutboxDispatcher();
        this.v2DiscordGateway = new com.dwurdy.straja.adapter.out.discord.DiscordWebhookGateway(
                policies.discordWebhookUrl);
        this.v2Outbox.recoverInFlight();
        this.v2Personnel.onAuthorized(record -> v2Outbox.enqueue(
                "PERSONNEL_AUTHORIZED", "personnel:" + record.playerUuid,
                com.dwurdy.straja.application.service.OutboxService.SafePayload.projection(
                        "PERSONNEL_AUTHORIZED", record.serviceNumber, record.playerUuid)));
        this.v2Promotions.onApproved(application -> v2Outbox.enqueue(
                "PROMOTION_COMPLETED", "promotion:" + application.applicationId,
                com.dwurdy.straja.application.service.OutboxService.SafePayload.projection(
                        "PROMOTION_COMPLETED", application.applicationId, application.subjectUuid)));
        this.v2Campaigns.onLifecycle(campaign -> {
            String type = campaign.status == com.dwurdy.straja.domain.model.MissionCampaign.CampaignStatus.ACTIVE
                    ? "CAMPAIGN_STARTED" : "CAMPAIGN_ENDED";
            v2Outbox.enqueue(type, type + ":" + campaign.campaignId,
                    com.dwurdy.straja.application.service.OutboxService.SafePayload.projection(
                            type, campaign.campaignId, campaign.stationId));
        });
        this.v2Missions.onPublished(mission -> v2Outbox.enqueue(
                "IMPORTANT_MISSION_CREATED", "mission:" + mission.id,
                com.dwurdy.straja.application.service.OutboxService.SafePayload.projection(
                        "IMPORTANT_MISSION_CREATED", mission.id, mission.beneficiaryUuid)));
        this.v2Consistency = new com.dwurdy.straja.application.service.ConsistencyService(
                personnelRepository, stationRepository, equipmentRepository, operationRepository,
                settlementRepository, campaignRepository, outboxRepository);
        this.v2ComplaintEscalation = new com.dwurdy.straja.application.service.ComplaintEscalationService(
                this.ctx.complaints(), clock, ids, v2Authorization,
                jurisdiction -> v2Personnel.findFirstByGrade(com.dwurdy.straja.domain.model.CareerGrade.INSPECTOR,
                        jurisdiction, clock.nowMillis()), policies.complaintSlaMillis);
        this.v2Stations.ensureDefault(this.ctx.setup().read());

        this.players = new PlayerService(ctx);
        this.players.useV2Authority(v2Personnel, v2Authorization);
        this.audit = new AuditService(ctx);
        this.v2ComplaintEscalation.useAuditService(this.audit);
        this.equipment = new EquipmentService(ctx);
        this.guards = new GuardService(ctx, players, audit, equipment, this::bootId);
        this.guards.useV2Promotions(v2Promotions);
        this.guards.useV2Settlements(v2Settlements);
        this.guards.useV2PersonnelProjection(player -> {
            try {
                v2Personnel.projectLegacy(player.uuid(), ctx.players().read(player.uuid()),
                        player.uuid().toString());
            } catch (RuntimeException ignored) {
                // Legacy projection is best-effort; V2 decisions still fail closed.
            }
        });
        this.guards.useV2PersonnelAuthorization((actor, target, rank) -> {
            try {
                var grade = switch (com.dwurdy.straja.domain.model.Rank.of(rank)) {
                    case STAGIAR -> com.dwurdy.straja.domain.model.CareerGrade.MILITARY_STAGIAR;
                    case GUARD -> com.dwurdy.straja.domain.model.CareerGrade.MILITARY_STRAJER;
                    case SERGENT -> com.dwurdy.straja.domain.model.CareerGrade.MILITARY_SERGENT;
                    case INSPECTOR -> com.dwurdy.straja.domain.model.CareerGrade.INSPECTOR;
                    default -> com.dwurdy.straja.domain.model.CareerGrade.MILITARY_STAGIAR;
                };
                v2Personnel.authorize(actor.uuid().toString(), target.uuid().toString(), grade,
                        grade.fullTimeRequired() ? com.dwurdy.straja.domain.model.EmploymentMode.FULL_TIME
                                : com.dwurdy.straja.domain.model.EmploymentMode.PART_TIME,
                        "COMMISSIONER_DIRECT", "hq", "authorize:" + target.uuid());
                return true;
            } catch (RuntimeException error) {
                return false;
            }
        });
        this.armory = new com.dwurdy.straja.application.service.ArmoryService(ctx, players, audit);
        this.npcs = new com.dwurdy.straja.application.service.NpcAdminService(ctx);
        this.missions = new com.dwurdy.straja.application.service.MissionService(ctx, players, audit);
        this.custody = new com.dwurdy.straja.application.service.CustodyService(ctx, players, audit);
        this.prison = new com.dwurdy.straja.application.service.PrisonService(ctx, players, audit, custody);
        this.fines = new com.dwurdy.straja.application.service.FineService(ctx, players, audit, prison);
        this.complaints = new com.dwurdy.straja.application.service.ComplaintService(ctx, players, audit);
        this.complaints.useV2Escalation(v2ComplaintEscalation);
        this.reports = new com.dwurdy.straja.application.service.ReportService(ctx, players, audit);
        this.audiences = new com.dwurdy.straja.application.service.AudienceService(ctx, players, audit);
        this.emergency = new com.dwurdy.straja.application.service.EmergencyService(ctx, players, audit);
        this.rooms = new com.dwurdy.straja.application.service.RoomService(ctx, players, audit, ctx.world());
        this.archive = new com.dwurdy.straja.application.service.ArchiveService(ctx, players, audit);
        this.identityCards = new com.dwurdy.straja.application.service.IdentityCardService(ctx, players, audit);
        this.secretary = new com.dwurdy.straja.application.service.SecretaryService();
        this.migration = new com.dwurdy.straja.application.service.MigrationService(ctx, audit);
        this.formSessions = new com.dwurdy.straja.application.service.FormSessionService(clock, ids, v2Documents);
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
        // Persisted policy overrides cannot re-enable Straja's generic downed
        // owner while an optional provider is present.
        OptionalModCompatibility.applyFailSafe(compatibility, policies);
        this.incidents = new com.dwurdy.straja.application.service.IncidentService(ctx, players, audit);
        this.bolos = new com.dwurdy.straja.application.service.BoloService(ctx, players, audit);
        this.evidence = new com.dwurdy.straja.application.service.EvidenceService(ctx, players, audit);
        this.arrestRecords = new com.dwurdy.straja.application.service.ArrestRecordService(ctx, players, audit);
        this.reputation = new com.dwurdy.straja.application.service.ReputationService(
                ctx, players, audit, incidents, bolos);
        this.guards.onRecruitmentEligibility(reputation::recruitmentAllowed);
        this.custody.onCustodyEscape((actor, target) -> {
            if (target != null) {
                reputation.recordCustodyEscape(actor, target,
                        target.uuid() + ":cut:" + ctx.clock().nowMillis());
            }
        });
        this.expansion = new com.dwurdy.straja.application.service.RpExpansionService(
                ctx, players, incidents, bolos, evidence, arrestRecords, reputation);
        this.prison.onArrest(expansion::startArrestRecord);
        this.prison.onSentenceCompleted((sentence, reason) -> {
            expansion.finalizeArrestRecord(sentence, reason);
            PlayerGateway target = ctx.server().findPlayer(sentence.targetUuid);
            if (target != null) {
                reputation.completeSentence(target, sentence.id);
            } else {
                try {
                    reputation.completeSentence(UUID.fromString(sentence.targetUuid),
                            sentence.target, sentence.id);
                } catch (IllegalArgumentException ignored) {
                    com.dwurdy.straja.StrajaMod.LOGGER.warn(
                            "[Straja] Could not apply offline sentence rehabilitation for {}", sentence.id);
                }
            }
            arrestRecords.refreshLinksForSubject(sentence.targetUuid);
        });
        this.fines.onFinePaid(fine -> {
            if (fine == null) return;
            PlayerGateway target = ctx.server().findPlayer(fine.targetUuid);
            if (target != null) {
                reputation.completeFinePayment(target, fine.id);
            } else {
                try {
                    reputation.completeFinePayment(UUID.fromString(fine.targetUuid),
                            fine.target, fine.id);
                } catch (IllegalArgumentException ignored) {
                    com.dwurdy.straja.StrajaMod.LOGGER.warn(
                            "[Straja] Could not apply offline fine rehabilitation for {}", fine.id);
                }
            }
            arrestRecords.refreshLinksForSubject(fine.targetUuid);
        });
        this.fines.onFineRefused(task -> {
            if (task == null || task.targetUuid == null || task.targetUuid.isBlank()) return;
            PlayerGateway target = ctx.server().findPlayer(task.targetUuid);
            if (target != null) reputation.recordFineRefusal(target, task.id);
            arrestRecords.refreshLinksForSubject(task.targetUuid);
        });
        this.fines.onFineVoided((actor, fine) -> {
            if (fine == null || fine.id == null || fine.id.isBlank()) return;
            var fineData = ctx.fines().read();
            if (fineData.tasks != null) {
                for (var task : fineData.tasks) {
                    if (task != null && fine.id.equals(task.fineId)) {
                        reputation.reverseSource(actor, "FINE_REFUSAL", task.id);
                    }
                }
            }
            arrestRecords.refreshLinksForSubject(fine.targetUuid);
        });
        this.guards.onStatusChange((p, reason) -> this.missions.cancelOpenFor(p, reason));
        this.guards.onStatusChange((p, reason) -> this.rooms.releaseFor(p));
        this.guards.onStatusChange((p, reason) -> this.incidents.removeAssignments(p, reason));
        this.guards.onPromotedToGuard(p -> this.rooms.assignAutomatically(p));
        this.admin = new com.dwurdy.straja.application.service.AdminService(
                ctx, players, guards, policyService, emergency);
        this.adminTools = new com.dwurdy.straja.application.service.AdminToolService(
                ctx, players, npcs, guards, prison);
    }

    public static synchronized StrajaRuntime start(MinecraftServer server) {
        instance = new StrajaRuntime(server);
        com.dwurdy.straja.adapter.out.network.CustodyVisualSync.reset();
        com.dwurdy.straja.adapter.in.form.FormSessionBridge.install(instance.formSessions);
        instance.npcFormRouter = new com.dwurdy.straja.adapter.in.form.FormSubmissionRouter(
                instance.guards, instance.missions, instance.complaints, instance.fines,
                instance.archive, instance.reports, instance.audiences, instance.admin,
                instance.adminTools, instance.custody, instance.expansion);
        // This is the reusable equivalent of the original
        // instance.adminTools, instance.custody, instance.expansion)::submit
        // wiring; NPC and native forms now share the same authenticated route.
        com.dwurdy.straja.adapter.in.form.FormPayloads.setSubmissionConsumer(
                instance.npcFormRouter::submit);
        instance.logDeploymentGates();
        // Absolute custody deadlines survive a server restart. Resolve any
        // already-due canonical states before the first login/tick callback.
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
        if (instance != null) instance.v2OutboxDispatcher.close();
        com.dwurdy.straja.adapter.out.network.CustodyVisualSync.reset();
        com.dwurdy.straja.adapter.in.form.FormSessionBridge.clear();
        com.dwurdy.straja.adapter.in.form.FormPayloads.setSubmissionConsumer(null);
        if (instance != null) instance.npcFormRouter = null;
        instance = null;
    }

    public static StrajaRuntime get() {
        return instance;
    }

    public MinecraftServer server() { return server; }
    public StrajaPolicies policies() { return policies; }
    public OptionalModCompatibility.Profile compatibility() { return compatibility; }
    public StrajaContext context() { return ctx; }
    public MinecraftServerGateway serverGateway() { return serverGateway; }
    public PlayerService players() { return players; }
    public AuditService audit() { return audit; }
    public EquipmentService equipment() { return equipment; }
    public GuardService guards() { return guards; }
    public com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase guardRecruitment() { return guards; }
    public com.dwurdy.straja.application.port.in.GuardDutyUseCase guardDuty() { return guards; }
    public com.dwurdy.straja.application.port.in.ArmoryUseCase armory() { return armory; }
    public com.dwurdy.straja.application.port.in.MissionRoleplayUseCase missionRoleplay() { return missions; }
    public com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase complaintRoleplay() { return complaints; }
    public com.dwurdy.straja.application.port.in.ReportUseCase reportRoleplay() { return reports; }
    public com.dwurdy.straja.application.port.in.AudienceUseCase audienceRoleplay() { return audiences; }
    public com.dwurdy.straja.application.port.in.EmergencyUseCase emergencyRoleplay() { return emergency; }
    public com.dwurdy.straja.application.port.in.AdminRoleplayUseCase adminRoleplay() { return admin; }
    public com.dwurdy.straja.application.port.in.AdminToolsUseCase adminTools() { return adminTools; }
    public com.dwurdy.straja.application.port.in.FineRoleplayUseCase fineRoleplay() { return fines; }
    public com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase custodyRoleplay() { return custody; }
    public com.dwurdy.straja.application.port.in.PrisonRoleplayUseCase prisonRoleplay() { return prison; }
    public com.dwurdy.straja.application.port.in.RoomRoleplayUseCase roomRoleplay() { return rooms; }
    public com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase archiveRoleplay() { return archive; }
    public com.dwurdy.straja.application.port.in.IdentityCardRoleplayUseCase identityCards() { return identityCards; }
    public com.dwurdy.straja.application.port.in.SecretaryRoleplayUseCase secretaryRoleplay() { return secretary; }
    public com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase expansionRoleplay() { return expansion; }
    public com.dwurdy.straja.application.service.RpExpansionService expansion() { return expansion; }
    public com.dwurdy.straja.application.service.IncidentService incidents() { return incidents; }
    public com.dwurdy.straja.application.service.BoloService bolos() { return bolos; }
    public com.dwurdy.straja.application.service.EvidenceService evidence() { return evidence; }
    public com.dwurdy.straja.application.service.ArrestRecordService arrestRecords() { return arrestRecords; }
    public com.dwurdy.straja.application.service.ReputationService reputation() { return reputation; }
    public com.dwurdy.straja.application.service.OperationRecoveryService v2Operations() { return v2Operations; }
    public com.dwurdy.straja.application.service.PersonnelService v2Personnel() { return v2Personnel; }
    public com.dwurdy.straja.application.service.AuthorizationService v2Authorization() { return v2Authorization; }
    public com.dwurdy.straja.application.service.PromotionService v2Promotions() { return v2Promotions; }
    public com.dwurdy.straja.application.service.StationService v2Stations() { return v2Stations; }
    public com.dwurdy.straja.application.service.DocumentService v2Documents() { return v2Documents; }
    public com.dwurdy.straja.application.service.EquipmentLedgerService v2EquipmentLedger() { return v2EquipmentLedger; }
    public com.dwurdy.straja.application.service.MobilizationService v2Mobilizations() { return v2Mobilizations; }
    public com.dwurdy.straja.application.service.CampaignService v2Campaigns() { return v2Campaigns; }
    public com.dwurdy.straja.application.service.SettlementService v2Settlements() { return v2Settlements; }
    public com.dwurdy.straja.application.service.MissionV2Service v2Missions() { return v2Missions; }
    public com.dwurdy.straja.application.service.MissionGeneratorService v2Generators() { return v2Generators; }
    public com.dwurdy.straja.application.service.OutboxService v2Outbox() { return v2Outbox; }
    public void dispatchOutbox() {
        if (!policies.discordWebhookUrl.isBlank())
            v2OutboxDispatcher.dispatch(v2Outbox, v2DiscordGateway, 8);
    }
    public com.dwurdy.straja.application.service.ComplaintEscalationService v2ComplaintEscalation() { return v2ComplaintEscalation; }
    public com.dwurdy.straja.application.service.ConsistencyService v2Consistency() { return v2Consistency; }
    public long nowMillis() { return clock.nowMillis(); }
    /** Calendar-week stipend hook; entitlement is persisted before payout. */
    public void settleWeeklyStipend(PlayerGateway player) {
        if (player == null || !policies.weeklyStipendEnabled || policies.weeklyStipendAmount <= 0) return;
        var state = players.state(player);
        long required = Math.max(0, policies.weeklyStipendRequiredServiceBlocks);
        boolean eligible = state != null && !state.suspended && !state.fired && !state.resigned
                && state.rank > 0 && state.serviceBlocks >= required;
        String weekId = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.nowMillis()),
                java.time.ZoneOffset.UTC).get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)
                + "-W" + String.format(java.util.Locale.ROOT, "%02d",
                java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.nowMillis()),
                        java.time.ZoneOffset.UTC).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        var settlement = v2Settlements.createWeeklyStipend(player.uuid().toString(), weekId,
                eligible ? "ELIGIBLE" : "INELIGIBLE", policies.weeklyStipendAmount, eligible);
        if (settlement != null && settlement.status != com.dwurdy.straja.domain.model.Settlement.SettlementStatus.PAID)
            v2Settlements.payout(settlement, player);
    }
    public void recoverV2(PlayerGateway player) {
        if (player == null) return;
        try {
            boolean configuredCommissioner = players.isConfiguredCommissioner(player);
            var legacy = ctx.players().read(player.uuid());
            var record = v2Personnel.projectLegacy(player.uuid(), legacy, player.uuid().toString());
            if (configuredCommissioner) {
                if (record == null) {
                    record = v2Personnel.authorize("bootstrap", player.uuid().toString(),
                            com.dwurdy.straja.domain.model.CareerGrade.INSPECTOR,
                            com.dwurdy.straja.domain.model.EmploymentMode.FULL_TIME,
                            "COMMISSIONER_BOOTSTRAP", "hq", "commissioner:" + player.uuid());
                }
                if (!record.hasAppointment(com.dwurdy.straja.domain.model.AppointmentType.COMMISSIONER, clock.nowMillis())) {
                    v2Personnel.appointInternal(player.uuid().toString(),
                            com.dwurdy.straja.domain.model.AppointmentType.COMMISSIONER, "hq", "", null);
                }
            }
            for (var settlement : v2Settlements.pendingFor(player.uuid().toString())) {
                if (settlement.status == com.dwurdy.straja.domain.model.Settlement.SettlementStatus.PENDING
                        || settlement.status == com.dwurdy.straja.domain.model.Settlement.SettlementStatus.FAILED_RETRYABLE
                        || settlement.status == com.dwurdy.straja.domain.model.Settlement.SettlementStatus.IN_PROGRESS) {
                    v2Settlements.payout(settlement, player);
                }
            }
            int pendingOperations = v2Operations.pendingFor(player.uuid().toString()).size();
            if (pendingOperations > 0) {
                com.dwurdy.straja.StrajaMod.LOGGER.info(
                        "[Straja] V2 recovery found {} pending operation(s) for {}", pendingOperations, player.uuid());
            }
        } catch (RuntimeException error) {
            com.dwurdy.straja.StrajaMod.LOGGER.warn(
                    "[Straja] V2 login projection failed closed for {}: {}", player.uuid(), error.getMessage());
        }
    }
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
    public void submitNpcForm(
            net.minecraft.server.level.ServerPlayer player,
            com.dwurdy.straja.application.port.in.FormSessionUseCase.Submission submission) {
        var router = npcFormRouter;
        if (router != null) router.submit(player, submission);
    }
    public com.dwurdy.straja.application.port.in.PolicyConfigUseCase policyConfig() { return policyService; }
    public com.dwurdy.straja.application.port.out.MutableClock clock() { return clock; }
    public com.dwurdy.straja.adapter.in.test.TestPlayerRegistry testPlayers() { return testPlayers; }
    public String bootId() { return bootId; }
}
