package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionUseCase;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider-neutral action gate. Provider adapters submit untrusted requests;
 * this service owns token binding, replay protection, surface availability,
 * provider ownership, and the server-side proximity/dispatch seams.
 */
public final class NpcSurfaceActionService
        implements NpcSurfaceActionUseCase, NpcSurfaceActionTokenIssuer {
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofSeconds(15);
    private static final Duration MAX_TOKEN_TTL = Duration.ofMinutes(5);
    private static final int MAX_ACTIVE_TOKENS = 4_096;
    private static final int MAX_ACTIVE_TOKENS_PER_PLAYER = 32;

    private final NpcSurfaceProviderRegistry providers;
    private final InteractionVerifier proximity;
    private final ActionDispatcher dispatcher;
    private final Clock clock;
    private final Duration tokenTtl;
    private final Map<String, PendingAction> tokens = new ConcurrentHashMap<>();
    private final Object tokenLock = new Object();

    public NpcSurfaceActionService(
            NpcSurfaceProviderRegistry providers,
            InteractionVerifier proximity,
            ActionDispatcher dispatcher) {
        this(providers, proximity, dispatcher, Clock.systemUTC(), DEFAULT_TOKEN_TTL);
    }

    public NpcSurfaceActionService(
            NpcSurfaceProviderRegistry providers,
            InteractionVerifier proximity,
            ActionDispatcher dispatcher,
            Clock clock,
            Duration tokenTtl) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.proximity = Objects.requireNonNull(proximity, "proximity");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(tokenTtl, "tokenTtl");
        if (tokenTtl.isNegative() || tokenTtl.isZero() || tokenTtl.compareTo(MAX_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException("tokenTtl must be positive and at most five minutes");
        }
        this.tokenTtl = tokenTtl;
    }

    /** Mints a player-bound token only for a currently enabled surface action. */
    @Override
    public String issueToken(UUID playerId, NpcBinding binding, NpcContentId actionId) {
        NpcSurfaceSnapshot published = providers.surface(binding.bindingId()).orElse(null);
        return issueToken(playerId, binding, actionId, published);
    }

    /**
     * Mints against the surface actually rendered to the player. The surface
     * is supplied by the provider adapter after its server-side resolver has
     * projected current state, so player-specific actions do not leak through
     * a binding-global snapshot.
     */
    @Override
    public String issueToken(
            UUID playerId,
            NpcBinding binding,
            NpcContentId actionId,
            NpcSurfaceSnapshot surface) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(actionId, "actionId");
        if (surface == null) {
            throw new IllegalStateException("cannot issue a token without a published surface");
        }
        synchronized (tokenLock) {
            purgeExpiredTokens();
            if (tokens.size() >= MAX_ACTIVE_TOKENS
                    || tokens.values().stream().filter(token -> token.playerId().equals(playerId)).count()
                            >= MAX_ACTIVE_TOKENS_PER_PLAYER) {
                throw new IllegalStateException("too many active NPC action tokens");
            }
            if (!providers.owns(binding.bindingId(), binding.providerId())
                    || !providers.binding(binding.bindingId()).filter(binding::equals).isPresent()) {
                throw new IllegalStateException("cannot issue a token for an unowned binding");
            }
            if (!binding.equals(surface.binding())) {
                throw new IllegalStateException("surface does not belong to binding");
            }
            boolean enabled = surface.actions().stream()
                    .anyMatch(action -> action.actionId().equals(actionId) && action.enabled());
            if (!enabled) {
                throw new IllegalStateException("cannot issue a token for an unavailable action");
            }
            String token;
            do {
                token = UUID.randomUUID().toString().replace("-", "");
            } while (tokens.putIfAbsent(token, new PendingAction(
                    playerId, binding.bindingId(), binding.providerId(), actionId, surface,
                    clock.instant().plus(tokenTtl), new AtomicBoolean(false), new AtomicReference<>())) != null);
            return token;
        }
    }

    @Override
    public NpcActionResult submit(NpcActionRequest request) {
        Objects.requireNonNull(request, "request");
        purgeExpiredTokens();
        NpcBinding binding = providers.binding(request.bindingId()).orElse(null);
        if (binding == null || !binding.providerId().equals(request.providerId())) {
            return result(NpcActionResult.Status.UNAUTHORIZED, "wrong-provider",
                    "NPC binding is not owned by this provider");
        }
        PendingAction pending = tokens.get(request.interactionToken());
        if (pending == null) {
            return result(NpcActionResult.Status.EXPIRED, "expired-or-replayed",
                    "NPC action token is no longer valid");
        }
        if (!pending.matches(request)) {
            return result(NpcActionResult.Status.UNAUTHORIZED, "token-mismatch",
                    "NPC action token does not belong to this request");
        }
        NpcActionResult previous = pending.outcome().get();
        if (previous != null) {
            return previous;
        }
        if (!clock.instant().isBefore(pending.expiresAt())) {
            tokens.remove(request.interactionToken(), pending);
            return result(NpcActionResult.Status.EXPIRED, "expired", "NPC action token expired");
        }
        boolean enabled = pending.surface().actions().stream()
                .anyMatch(action -> action.actionId().equals(request.actionId()) && action.enabled());
        if (!enabled) {
            return result(NpcActionResult.Status.REJECTED, "action-unavailable",
                    "NPC action is no longer available");
        }
        if (!proximity.playerAtBinding(request.playerId(), binding)) {
            return result(NpcActionResult.Status.UNAUTHORIZED, "out-of-range",
                    "player is not at the NPC binding");
        }
        if (!pending.claimed().compareAndSet(false, true)) {
            NpcActionResult inFlight = pending.outcome().get();
            return inFlight != null ? inFlight : result(
                    NpcActionResult.Status.REJECTED, "in-flight", "NPC action is already being processed");
        }
        NpcActionResult dispatchResult;
        try {
            dispatchResult = Objects.requireNonNull(dispatcher.dispatch(request), "dispatcher result");
        } catch (RuntimeException exception) {
            dispatchResult = result(NpcActionResult.Status.UNAVAILABLE, "dispatch-failed",
                    "NPC action could not be dispatched");
        }
        if (dispatchResult.status() == NpcActionResult.Status.UNAVAILABLE) {
            // Keep the same outcome attached to the token. Replaying the token
            // cannot execute the dispatcher a second time or create a second
            // durable effect after an uncertain external operation.
        }
        pending.outcome().set(dispatchResult);
        return dispatchResult;
    }

    /** Called from the server tick as well as issuance/submission paths. */
    public void purgeExpiredTokens() {
        synchronized (tokenLock) {
            Instant now = clock.instant();
            tokens.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        }
    }

    private static NpcActionResult result(NpcActionResult.Status status, String code, String message) {
        return new NpcActionResult(status, code, message);
    }

    @FunctionalInterface
    public interface InteractionVerifier {
        boolean playerAtBinding(UUID playerId, NpcBinding binding);
    }

    @FunctionalInterface
    public interface ActionDispatcher {
        /**
         * Must use request.interactionToken() as its idempotency key. The
         * action token retains the first outcome, including UNAVAILABLE, so a
         * replay cannot invoke this dispatcher twice.
         */
        NpcActionResult dispatch(NpcActionRequest request);
    }

    private record PendingAction(
            UUID playerId,
            String bindingId,
            NpcProviderId providerId,
            NpcContentId actionId,
            NpcSurfaceSnapshot surface,
            Instant expiresAt,
            AtomicBoolean claimed,
            AtomicReference<NpcActionResult> outcome) {
        boolean matches(NpcActionRequest request) {
            return playerId.equals(request.playerId())
                    && bindingId.equals(request.bindingId())
                    && providerId.equals(request.providerId())
                    && actionId.equals(request.actionId());
        }
    }
}
