package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.out.persistence.NbtNpcBindingRepository;
import com.dwurdy.straja.adapter.out.persistence.NbtStore;
import com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader;
import com.dwurdy.straja.adapter.out.npc.customnpcs.CustomNpcsNpcSurfaceProvider;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.service.NpcBindingLifecycleService;
import com.dwurdy.straja.application.service.NpcAdmissionSurfaceService;
import com.dwurdy.straja.application.service.NpcArmorySurfaceService;
import com.dwurdy.straja.application.service.NpcCareerSurfaceService;
import com.dwurdy.straja.application.service.NpcContentCatalog;
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
    private static final NpcArmorySurfaceService ARMORY_SURFACE = new NpcArmorySurfaceService();
    private static final NpcCareerSurfaceService CAREER_SURFACE = new NpcCareerSurfaceService();

    private NpcPresentationRuntime() {}

    public static synchronized void start(MinecraftServer server) {
        if (STATE.get() != null) return;
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        NpcSurfaceActionService actions = new NpcSurfaceActionService(
                providers,
                (playerId, binding) -> playerAtBinding(server, playerId, binding),
                request -> dispatch(server, request));
        CustomNpcsNpcSurfaceProvider customNpcs = new CustomNpcsNpcSurfaceProvider(
                actions,
                actions,
                message -> StrajaMod.LOGGER.info("{}", message),
                NpcPresentationRuntime::resolveSurface);
        providers.register(customNpcs);
        StoreAccess stores = name -> new NbtStore(StrajaDataProvider.get(server, name));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers,
                new NbtNpcBindingRepository(stores),
                loadCatalog());
        NpcBindingLifecycleService.RecoveryReport recovery = lifecycle.recover();
        recovery.items().stream()
                .filter(item -> item.result().status() != NpcProviderResult.Status.ACCEPTED)
                .forEach(item -> StrajaMod.LOGGER.warn(
                        "NPC binding recovery pending: {} ({})",
                        item.bindingId(), item.result().code()));
        STATE.set(new RuntimeState(
                server,
                providers,
                actions,
                customNpcs,
                lifecycle));
        StrajaMod.LOGGER.info("NPC presentation runtime started; CustomNPCs available={}",
                customNpcs.available());
    }

    public static synchronized void stop() {
        STATE.set(null);
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
            String hostEntityUuid, String roleId, String stationId) {
        String role = "recruiter".equals(roleId) ? "trainer" : roleId;
        if (!"receptionist".equals(role) && !"trainer".equals(role)
                && !"armorer".equals(role)) {
            return NpcProviderResult.rejected(
                    "unsupported-role", "supported CustomNPC roles are receptionist, trainer, and armorer");
        }
        NpcContentId profile = "receptionist".equals(role)
                ? NpcContentId.of("straja.reception.admission")
                : "armorer".equals(role)
                        ? NpcContentId.of("straja.armorer.orders")
                        : NpcContentId.of("straja.instructor.admission");
        String normalizedUuid;
        try {
            normalizedUuid = UUID.fromString(hostEntityUuid).toString();
        } catch (IllegalArgumentException exception) {
            return NpcProviderResult.rejected("invalid-host-identity", "hostEntityUuid must be a UUID");
        }
        NpcBinding binding = new NpcBinding(
                "straja.customnpcs." + role + "." + normalizedUuid.replace("-", ""),
                NpcProviderId.CUSTOM_NPCS,
                normalizedUuid,
                "",
                role,
                stationId == null || stationId.isBlank() ? "hq" : stationId,
                profile,
                1);
        try {
            return bindAndPublish(binding);
        } catch (IllegalStateException exception) {
            return NpcProviderResult.unavailable("NPC presentation runtime is not started");
        }
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
                try (var armorerStream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                        "data/straja/npc/straja.armorer.orders.json")) {
                    if (armorerStream == null) {
                        throw new IllegalStateException("armorer NPC profile resource is missing");
                    }
                    NpcContentProfile armorer = loader.load(
                            new InputStreamReader(armorerStream, StandardCharsets.UTF_8));
                    return new NpcContentCatalog(List.of(reception, instructor, armorer));
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
        GuardState state = runtime.playerQueries().readState(player);
        if (state == null) return published;
        if ("armorer".equals(binding.roleId())) {
            return ARMORY_SURFACE.resolve(published, runtime.armory().offers(player));
        }
        java.util.Optional<com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase.QuizPrompt> prompt =
                java.util.Optional.empty();
        if (("trainer".equals(binding.roleId()) || "recruiter".equals(binding.roleId()))
                && !state.fired && !state.suspended && !state.resigned
                && (state.invited || "APPLIED".equals(state.applicationState))) {
            prompt = runtime.guardRecruitment().currentQuizPrompt(player);
        }
        NpcSurfaceSnapshot admission = ADMISSION_SURFACE.resolve(published, state, prompt);
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
        boolean handled = binding != null && admissionAction(binding.roleId(), request.actionId().value())
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
            NpcBindingLifecycleService lifecycle) {}
}
