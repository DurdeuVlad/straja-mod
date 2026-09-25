package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.out.persistence.NbtNpcBindingRepository;
import com.dwurdy.straja.adapter.out.persistence.NbtStore;
import com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader;
import com.dwurdy.straja.adapter.out.npc.customnpcs.CustomNpcsNpcSurfaceProvider;
import com.dwurdy.straja.adapter.out.npc.debug.DebugTextNpcSurfaceProvider;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.service.NpcBindingLifecycleService;
import com.dwurdy.straja.application.service.NpcBindingRoleResolver;
import com.dwurdy.straja.application.service.NpcAdmissionSurfaceService;
import com.dwurdy.straja.application.service.NpcArchiveSurfaceService;
import com.dwurdy.straja.application.service.NpcArmorySurfaceService;
import com.dwurdy.straja.application.service.NpcCareerSurfaceService;
import com.dwurdy.straja.application.service.NpcCivicSurfaceService;
import com.dwurdy.straja.application.service.NpcCustodySurfaceService;
import com.dwurdy.straja.application.service.NpcContentCatalog;
import com.dwurdy.straja.application.service.NpcProvisioningService;
import com.dwurdy.straja.application.service.NpcProviderMigrationService;
import com.dwurdy.straja.application.service.NpcSecretarySurfaceService;
import com.dwurdy.straja.application.service.NpcSurfaceActionService;
import com.dwurdy.straja.application.service.NpcSurfaceProviderRegistry;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import com.dwurdy.straja.domain.model.GuardState;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Server lifecycle composition root for provider-neutral NPC presentation.
 * Binding/content APIs are intentionally small so admin tooling can be added
 * without exposing provider objects to the application layer.
 */
public final class NpcPresentationRuntime {
    private static final AtomicReference<RuntimeState> STATE = new AtomicReference<>();
    private static final NpcAdmissionSurfaceService ADMISSION_SURFACE = new NpcAdmissionSurfaceService();
    private static final NpcArchiveSurfaceService ARCHIVE_SURFACE = new NpcArchiveSurfaceService();
    private static final NpcArmorySurfaceService ARMORY_SURFACE = new NpcArmorySurfaceService();
    private static final NpcCareerSurfaceService CAREER_SURFACE = new NpcCareerSurfaceService();
    private static final NpcCivicSurfaceService CIVIC_SURFACE = new NpcCivicSurfaceService();
    private static final NpcCustodySurfaceService CUSTODY_SURFACE = new NpcCustodySurfaceService();
    private static final NpcSecretarySurfaceService SECRETARY_SURFACE = new NpcSecretarySurfaceService();

    private NpcPresentationRuntime() {}

    public static synchronized void start(MinecraftServer server) {
        if (STATE.get() != null) return;
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        NpcProviderId configuredProvider = configuredProvider();
        boolean debugTextEnabled = debugTextAllowed();
        NpcSurfaceActionService actions = new NpcSurfaceActionService(
                providers,
                (playerId, binding) -> playerAtBinding(server, playerId, binding),
                request -> dispatch(server, request));
        NpcContentCatalog catalog = loadCatalog();
        StoreAccess stores = name -> new NbtStore(StrajaDataProvider.get(server, name));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers,
                new NbtNpcBindingRepository(stores),
                catalog);
        NpcProvisioningService provisioning = new NpcProvisioningService(
                lifecycle, catalog, providers);
        CustomNpcsNpcSurfaceProvider customNpcs = new CustomNpcsNpcSurfaceProvider(
                actions,
                actions,
                message -> StrajaMod.LOGGER.info("{}", message),
                NpcPresentationRuntime::resolveSurface,
                provisioning,
                player -> player.hasPermissions(2)
                        && player.getMainHandItem().is(StrajaItems.NPC_WAND.get()));
        providers.register(customNpcs);
        if (debugTextEnabled) {
            providers.register(new DebugTextNpcSurfaceProvider(
                    message -> StrajaMod.LOGGER.info("{}", message)));
        }
        NpcBindingLifecycleService.RecoveryReport recovery = lifecycle.recover();
        recovery.items().stream()
                .filter(item -> item.result().status() != NpcProviderResult.Status.ACCEPTED)
                .forEach(item -> StrajaMod.LOGGER.warn(
                        "NPC binding recovery pending: {} ({})",
                        item.bindingId(), item.result().code()));
        NpcProviderMigrationService migration = new NpcProviderMigrationService(
                lifecycle, providers, catalog);
        if (configuredProvider.equals(NpcProviderId.DEBUG_TEXT) && !debugTextEnabled) {
            StrajaMod.LOGGER.error(
                    "NPC provider mode is debug-text but npc.debugTextEnabled and debug security gates do not permit it; provider remains unavailable");
        }
        STATE.set(new RuntimeState(
                server,
                providers,
                actions,
                customNpcs,
                lifecycle,
                provisioning,
                migration,
                configuredProvider,
                debugTextEnabled));
        StrajaMod.LOGGER.info(
                "NPC presentation runtime started; mode={} CustomNPCs available={} debug-text enabled={}",
                configuredProvider.value(), customNpcs.available(), debugTextEnabled);
    }

    public static synchronized void stop() {
        STATE.set(null);
    }

    /**
     * Routes the early Minecraft left-click boundary to the active optional
     * provider. Keeping the raw entity out of the application services lets
     * StoryNPC implement the same provisioning contract later without
     * changing the canonical profile or action model.
     */
    public static boolean handleCustomNpcAdminAttack(ServerPlayer player, Entity target) {
        RuntimeState state = STATE.get();
        return state != null
                && state.activeProvider().equals(NpcProviderId.CUSTOM_NPCS)
                && state.customNpcs().handleAdminAttack(player, target);
    }

    public static boolean isCustomNpcTarget(Entity target) {
        return CustomNpcsNpcSurfaceProvider.isCustomNpcsEntity(target);
    }

    public static void forgetCustomNpcAdminAttack(UUID playerId) {
        RuntimeState state = STATE.get();
        if (state != null) state.customNpcs().forgetAdminAttack(playerId);
    }

    public static RuntimeState require() {
        RuntimeState state = STATE.get();
        if (state == null) throw new IllegalStateException("NPC presentation runtime is not started");
        return state;
    }

    /** Binds and publishes a canonical profile through the active provider. */
    public static NpcProviderResult bindAndPublish(NpcBinding binding) {
        RuntimeState state = require();
        return state.lifecycle().bindAndPublish(binding);
    }

    /**
     * Explicit setup seam for an externally hosted NPC. The provider owns the
     * host interaction only after this mapping is accepted and published.
     */
    public static NpcProviderResult bindCustomNpc(
            String hostEntityUuid, String roleId, String stationId, String actorId) {
        RuntimeState active = STATE.get();
        if (active == null || !active.activeProvider().equals(NpcProviderId.CUSTOM_NPCS)) {
            return NpcProviderResult.unavailable(
                    "CustomNPCs is not the configured active NPC provider");
        }
        String role = "recruiter".equals(roleId) ? "trainer" : roleId;
        if (!"receptionist".equals(role) && !"trainer".equals(role)
                && !"secretary".equals(role)
                && !"jailer".equals(role)
                && !"armorer".equals(role)
                && !"archivist".equals(role)) {
            return NpcProviderResult.rejected(
                    "unsupported-role", "supported CustomNPC roles are receptionist, trainer, secretary, jailer, armorer, and archivist");
        }
        NpcContentId profile = "receptionist".equals(role)
                ? NpcContentId.of("straja.reception.admission")
                : "secretary".equals(role)
                        ? NpcContentId.of("straja.secretary.workflows")
                        : "jailer".equals(role)
                        ? NpcContentId.of("straja.jailer.custody")
                        : "armorer".equals(role)
                        ? NpcContentId.of("straja.armorer.orders")
                        : "archivist".equals(role)
                        ? NpcContentId.of("straja.archivist.archive")
                        : NpcContentId.of("straja.instructor.admission");
        String normalizedUuid;
        try {
            normalizedUuid = UUID.fromString(hostEntityUuid).toString();
        } catch (IllegalArgumentException exception) {
            return NpcProviderResult.rejected("invalid-host-identity", "hostEntityUuid must be a UUID");
        }
        try {
            com.dwurdy.straja.application.port.in.NpcProvisioningUseCase.ProvisioningResult result = require().provisioning().assign(
                    NpcProviderId.CUSTOM_NPCS,
                    normalizedUuid,
                    actorId,
                    profile.value(),
                    stationId);
            return toProviderResult(result);
        } catch (IllegalStateException exception) {
            return NpcProviderResult.unavailable("NPC presentation runtime is not started");
        }
    }

    /** Whether a provider-owned or unresolved canonical Straja binding claims this host. */
    public static boolean hasBindingForHost(String hostEntityUuid) {
        RuntimeState state = STATE.get();
        return state != null && NpcBindingRoleResolver.hasBindingForHost(state.lifecycle(), hostEntityUuid);
    }

    /** Canonical provider-neutral role lookup; jailer wins if corrupt duplicate roles exist. */
    public static Optional<String> assignedRoleForHost(String hostEntityUuid) {
        RuntimeState state = STATE.get();
        return state == null
                ? Optional.empty()
                : NpcBindingRoleResolver.assignedRoleForHost(state.lifecycle(), hostEntityUuid);
    }

    private static NpcProviderResult toProviderResult(
            com.dwurdy.straja.application.port.in.NpcProvisioningUseCase.ProvisioningResult result) {
        return switch (result.status()) {
            case ACCEPTED -> NpcProviderResult.accepted(result.message());
            case REJECTED -> NpcProviderResult.rejected(result.code(), result.message());
            case UNAVAILABLE -> NpcProviderResult.unavailable(result.message());
            case UNKNOWN -> NpcProviderResult.unknown(result.message());
        };
    }

    private static NpcContentCatalog loadCatalog() {
        try (var stream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.reception.admission.json")) {
            if (stream == null) {
                throw new IllegalStateException("default NPC profile resource is missing");
            }
            var loader = new NpcContentProfileJsonLoader();
            NpcContentProfile reception = loader.load(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            try (var instructorStream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                    "data/straja/npc/straja.instructor.admission.json")) {
                if (instructorStream == null) {
                    throw new IllegalStateException("instructor NPC profile resource is missing");
                }
                NpcContentProfile instructor = loader.load(
                        new InputStreamReader(instructorStream, StandardCharsets.UTF_8));
                try (var secretaryStream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                        "data/straja/npc/straja.secretary.workflows.json")) {
                    if (secretaryStream == null) {
                        throw new IllegalStateException("secretary NPC profile resource is missing");
                    }
                    NpcContentProfile secretary = loader.load(
                            new InputStreamReader(secretaryStream, StandardCharsets.UTF_8));
                    try (var armorerStream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                            "data/straja/npc/straja.armorer.orders.json")) {
                        if (armorerStream == null) {
                            throw new IllegalStateException("armorer NPC profile resource is missing");
                        }
                            NpcContentProfile armorer = loader.load(
                                    new InputStreamReader(armorerStream, StandardCharsets.UTF_8));
                            try (var jailerStream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                                    "data/straja/npc/straja.jailer.custody.json")) {
                            if (jailerStream == null) {
                                throw new IllegalStateException("jailer NPC profile resource is missing");
                            }
                                NpcContentProfile jailer = loader.load(
                                        new InputStreamReader(jailerStream, StandardCharsets.UTF_8));
                            try (var archivistStream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                                    "data/straja/npc/straja.archivist.archive.json")) {
                                if (archivistStream == null) {
                                    throw new IllegalStateException("archivist NPC profile resource is missing");
                                }
                                NpcContentProfile archivist = loader.load(
                                        new InputStreamReader(archivistStream, StandardCharsets.UTF_8));
                                return new NpcContentCatalog(
                                        List.of(reception, instructor, secretary, jailer, armorer, archivist));
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("NPC content catalog failed to load", exception);
        }
    }

    private static NpcSurfaceSnapshot resolveSurface(
            UUID playerId, NpcBinding binding, NpcSurfaceSnapshot published) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return published;
        PlayerGateway player = new com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway(
                runtime.server(), playerId);
        if ("archivist".equals(binding.roleId())) {
            return ARCHIVE_SURFACE.resolve(
                    published,
                    runtime.archiveRoleplay().availableActions(player),
                    runtime.archiveRoleplay().limits());
        }
        GuardState state = runtime.playerQueries().readState(player);
        if (state == null) return published;
        if ("armorer".equals(binding.roleId())) {
            return ARMORY_SURFACE.resolve(published, runtime.armory().offers(player));
        }
        if ("jailer".equals(binding.roleId())) {
            return CUSTODY_SURFACE.resolve(
                    published,
                    runtime.custodyRoleplay().availableActions(player),
                    runtime.playerQueries().isCommissioner(player)
                            || runtime.playerQueries().isOnDutyGuard(player),
                    runtime.custodyRoleplay().isCuffed(player),
                    runtime.custodyRoleplay().isBound(player),
                    runtime.custodyRoleplay().isDowned(player),
                    runtime.prisonRoleplay().activeSentence(player));
        }
        if ("secretary".equals(binding.roleId())) {
            return SECRETARY_SURFACE.resolve(published, new NpcSecretarySurfaceService.Inputs(
                    runtime.missionRoleplay().availableActions(player),
                    runtime.complaintRoleplay().availableActions(player),
                    runtime.fineRoleplay().availableActions(player),
                    runtime.reportRoleplay().availableActions(player),
                    runtime.audienceRoleplay().availableActions(player),
                    runtime.expansionRoleplay().activeIncidents(player),
                    runtime.expansionRoleplay().activeBolos(player),
                    runtime.adminRoleplay().availableActions(player),
                    runtime.guardDuty().dutyView(player),
                    runtime.complaintRoleplay().limits(),
                    runtime.fineRoleplay().limits()));
        }
        java.util.Optional<com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase.QuizPrompt> prompt =
                java.util.Optional.empty();
        if (("trainer".equals(binding.roleId()) || "recruiter".equals(binding.roleId()))
                && !state.fired && !state.suspended && !state.resigned
                && (state.invited || "APPLIED".equals(state.applicationState))) {
            prompt = runtime.guardRecruitment().currentQuizPrompt(player);
        }
        NpcSurfaceSnapshot admission = ADMISSION_SURFACE.resolve(published, state, prompt);
        if ("receptionist".equals(binding.roleId())) {
            return CIVIC_SURFACE.resolve(
                    admission,
                    runtime.complaintRoleplay().availableActions(player),
                    runtime.fineRoleplay().availableActions(player),
                    runtime.complaintRoleplay().limits(),
                    runtime.fineRoleplay().limits());
        }
        if ("trainer".equals(binding.roleId()) || "recruiter".equals(binding.roleId())) {
            return CAREER_SURFACE.resolve(
                    admission, state, runtime.guardRecruitment().trainingView(player));
        }
        return admission;
    }

    private static boolean playerAtBinding(MinecraftServer server, UUID playerId, NpcBinding binding) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return false;
        Entity host = findEntity(server, binding.hostEntityUuid());
        return host != null && host.level() == player.level()
                && host.distanceToSqr(player) <= 64.0D;
    }

    private static Entity findEntity(MinecraftServer server, String entityUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(entityUuid);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    private static NpcActionResult dispatch(MinecraftServer server, NpcActionRequest request) {
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null) {
            return new NpcActionResult(
                    NpcActionResult.Status.UNAUTHORIZED, "player-offline", "player is not online");
        }
        RuntimeState state = STATE.get();
        NpcBinding binding = state == null
                ? null
                : state.lifecycle().inspect(request.bindingId()).binding();
        boolean handled = binding != null && "secretary".equals(binding.roleId())
                && dispatchSecretaryForm(player, request)
                ? true
                : binding != null && "receptionist".equals(binding.roleId())
                && dispatchReceptionistForm(player, request)
                ? true
                : binding != null && "jailer".equals(binding.roleId())
                && dispatchJailerForm(player, request)
                ? true
                : binding != null && "archivist".equals(binding.roleId())
                && dispatchArchivistAction(player, request)
                ? true
                : binding != null && admissionAction(binding.roleId(), request.actionId().value())
                ? dispatchAdmissionAction(player, request)
                : com.dwurdy.straja.adapter.in.npc.NpcRoles.performAction(
                        request.actionId().value(), player, player.serverLevel());
        return handled
                ? new NpcActionResult(NpcActionResult.Status.ACCEPTED, "dispatched", "action dispatched")
                : new NpcActionResult(
                        NpcActionResult.Status.REJECTED,
                        "action-rejected",
                        "the action is no longer available");
    }

    private static boolean admissionAction(String roleId, String actionId) {
        boolean admissionRole = "receptionist".equals(roleId)
                || "trainer".equals(roleId)
                || "recruiter".equals(roleId);
        return admissionRole && Set.of(
                "application-submit", "recruit", "quiz-answer", "training-progress", "training-manual")
                .contains(actionId);
    }

    private static boolean dispatchSecretaryForm(ServerPlayer player, NpcActionRequest request) {
        String raw = request.actionId().value();
        int separator = raw.indexOf(':');
        String operation = separator < 0 ? raw : raw.substring(0, separator);
        String recordId = separator < 0 ? "" : raw.substring(separator + 1);
        com.dwurdy.straja.application.port.in.FormSessionUseCase.Action action = switch (operation) {
            case "mission-draft-write" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.MISSION_DRAFT_WRITE;
            case "mission-draft-scope" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.MISSION_DRAFT_SCOPE;
            case "mission-budget-adjust" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.MISSION_BUDGET_ADJUST;
            case "mission-report" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.MISSION_REPORT;
            case "mission-fail" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.MISSION_FAIL;
            case "complaint-report" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.COMPLAINT_REPORT;
            case "complaint-withdraw" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.COMPLAINT_WITHDRAW;
            case "complaint-review" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.COMPLAINT_REVIEW;
            case "fine-draft-write" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.FINE_DRAFT;
            case "fine-warrant" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.FINE_WARRANT;
            case "report-submit" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.REPORT_SUBMIT;
            case "report-review" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.REPORT_REVIEW;
            case "audience-request" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.AUDIENCE_REQUEST;
            case "audience-review" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.AUDIENCE_REVIEW;
            case "bolo-create" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.BOLO_CREATE;
            case "incident-resolve" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.INCIDENT_RESOLVE;
            case "admin-authorize" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ADMIN_AUTHORIZE;
            case "admin-policy-set" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ADMIN_POLICY_SET;
            case "admin-emergency-alert" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ADMIN_EMERGENCY_ALERT;
            case "admin-emergency-start" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ADMIN_EMERGENCY_START;
            default -> null;
        };
        if (action == null) return false;
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return false;
        runtime.submitNpcForm(player, new com.dwurdy.straja.application.port.in.FormSessionUseCase.Submission(
                action, recordId, request.input()));
        return true;
    }

    private static boolean dispatchReceptionistForm(ServerPlayer player, NpcActionRequest request) {
        String raw = request.actionId().value();
        int separator = raw.indexOf(':');
        String operation = separator < 0 ? raw : raw.substring(0, separator);
        String recordId = separator < 0 ? "" : raw.substring(separator + 1);
        com.dwurdy.straja.application.port.in.FormSessionUseCase.Action action = switch (operation) {
            case "complaint-submit" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.COMPLAINT_SUBMIT;
            case "complaint-withdraw" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.COMPLAINT_WITHDRAW;
            case "fine-appeal" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.FINE_APPEAL;
            case "fine-appeal-review" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.FINE_APPEAL_REVIEW;
            default -> null;
        };
        if (action == null) return false;
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return false;
        runtime.submitNpcForm(player, new com.dwurdy.straja.application.port.in.FormSessionUseCase.Submission(
                action, recordId, request.input()));
        return true;
    }

    private static boolean dispatchJailerForm(ServerPlayer player, NpcActionRequest request) {
        if (!"arrest-handoff".equals(request.actionId().value())) return false;
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return false;
        runtime.submitNpcForm(player, new com.dwurdy.straja.application.port.in.FormSessionUseCase.Submission(
                com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARREST_HANDOFF,
                "", request.input()));
        return true;
    }

    private static boolean dispatchArchivistAction(ServerPlayer player, NpcActionRequest request) {
        String raw = request.actionId().value();
        int separator = raw.indexOf(':');
        String operation = separator < 0 ? raw : raw.substring(0, separator);
        String recordId = separator < 0 ? "" : raw.substring(separator + 1);
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return false;
        PlayerGateway gateway = new com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway(
                runtime.server(), player.getUUID());
        switch (operation) {
            case "archive-list" -> {
                runtime.archiveRoleplay().listFolders(gateway);
                return true;
            }
            case "archive-folder-read" -> {
                runtime.archiveRoleplay().readFolder(gateway, recordId);
                return true;
            }
            case "archive-sheet-read" -> {
                runtime.archiveRoleplay().readSheet(gateway, recordId);
                return true;
            }
            case "archive-sheet-submit" -> {
                return runtime.archiveRoleplay().submitSheet(gateway, recordId);
            }
            case "archive-sheet-revoke" -> {
                return runtime.archiveRoleplay().revokeSheet(gateway, recordId);
            }
            default -> { }
        }
        com.dwurdy.straja.application.port.in.FormSessionUseCase.Action action = switch (operation) {
            case "archive-folder-create" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_FOLDER_CREATE;
            case "archive-folder-issue" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_FOLDER_ISSUE;
            case "archive-sheet-new" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_SHEET_NEW;
            case "archive-sheet-edit" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_SHEET_EDIT;
            case "archive-recipients" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_RECIPIENTS;
            case "archive-sheet-sign" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_SIGN;
            case "archive-sheet-copy" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_COPY;
            case "archive-sheet-envelope" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_ENVELOPE;
            case "archive-sheet-issue" -> com.dwurdy.straja.application.port.in.FormSessionUseCase.Action.ARCHIVE_DOCUMENT_ISSUE;
            default -> null;
        };
        if (action == null) return false;
        runtime.submitNpcForm(player, new com.dwurdy.straja.application.port.in.FormSessionUseCase.Submission(
                action, recordId, request.input()));
        return true;
    }

    private static boolean dispatchAdmissionAction(ServerPlayer player, NpcActionRequest request) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return false;
        PlayerGateway gateway = new com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway(
                runtime.server(), player.getUUID());
        return switch (request.actionId().value()) {
            case "application-submit" -> {
                runtime.guardRecruitment().applyForStraja(gateway);
                yield true;
            }
            case "recruit" -> {
                runtime.guardRecruitment().currentQuizPrompt(gateway);
                yield true;
            }
            case "quiz-answer" -> {
                String questionId = request.input().get("question-id");
                String answer = request.input().get("answer");
                if (questionId == null || questionId.isBlank() || answer == null
                        || answer.isBlank() || answer.length() > 120) {
                    yield false;
                }
                yield runtime.guardRecruitment().answerQuiz(gateway, questionId, answer);
            }
            case "training-progress" -> {
                runtime.guardRecruitment().showProgress(gateway);
                yield true;
            }
            case "training-manual" -> {
                runtime.guardRecruitment().giveManual(gateway);
                yield true;
            }
            default -> false;
        };
    }

    public record RuntimeState(
            MinecraftServer server,
            NpcSurfaceProviderRegistry providers,
            NpcSurfaceActionTokenIssuer actions,
            CustomNpcsNpcSurfaceProvider customNpcs,
            NpcBindingLifecycleService lifecycle,
            NpcProvisioningService provisioning,
            NpcProviderMigrationService migration,
            NpcProviderId activeProvider,
            boolean debugTextEnabled) {}

    private static NpcProviderId configuredProvider() {
        StrajaRuntime runtime = StrajaRuntime.get();
        String configured = runtime == null ? "customnpcs" : runtime.policies().npcProviderMode;
        try {
            return NpcProviderId.of(configured);
        } catch (IllegalArgumentException error) {
            StrajaMod.LOGGER.error("Invalid NPC provider mode '{}'; falling back to customnpcs", configured);
            return NpcProviderId.CUSTOM_NPCS;
        }
    }

    private static boolean debugTextAllowed() {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return false;
        var policies = runtime.policies();
        if (!policies.npcDebugTextEnabled || !policies.debugEnabled) return false;
        if (policies.requireDebugDisabledOutsideLocal && !policies.isLocalEnvironment()) return false;
        return !policies.debugLocalOnly || policies.isLocalEnvironment();
    }
}
