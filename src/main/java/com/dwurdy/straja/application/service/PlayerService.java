package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.PermissionLevel;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Result;
import java.util.UUID;

/**
 * Identity, permission resolution and player-state access. This is the only
 * place that decides who is commissioner and what an actor may do; rank text,
 * NPC dialogue or item names can never grant authority.
 */
public class PlayerService implements com.dwurdy.straja.application.port.in.PlayerQueryUseCase {
    private final StrajaContext ctx;

    public PlayerService(StrajaContext ctx) {
        this.ctx = ctx;
    }

    public GuardState state(PlayerGateway player) {
        return ctx.players().read(player.uuid());
    }

    public GuardState state(UUID uuid) {
        return ctx.players().read(uuid);
    }

    public void save(UUID uuid, GuardState state) {
        state.refreshLifecycle();
        ctx.players().write(uuid, state);
    }

    public static String canon(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    /** UUID wins over name; debug override wins in local/test mode only. */
    public boolean isCommissioner(String name, UUID uuid) {
        var policies = ctx.policies();
        var test = ctx.test().read();
        if (policies.debugEnabled && policies.isLocalEnvironment()
                && policies.debugAllowCommissionerOverride
                && uuid != null && uuid.toString().equals(test.debugCommissionerUuid)) {
            return true;
        }
        String configuredUuid = canon(policies.commissionerUuid);
        String configuredName = canon(policies.commissionerName);
        String playerUuid = uuid == null ? "" : uuid.toString();
        // Outside local, the deployment gate pins the commissioner by UUID only:
        // usernames are spoofable on offline-mode servers, so a missing or
        // mismatched pin must deny rather than fall back to a name match.
        boolean pinRequired = !policies.isLocalEnvironment()
                && (policies.requireCommissionerUuidOutsideLocal || policies.requireUuid);
        if (pinRequired) {
            return !configuredUuid.isEmpty() && playerUuid.equalsIgnoreCase(configuredUuid);
        }
        boolean uuidConfigured = !configuredUuid.isEmpty();
        if (uuidConfigured) {
            if (playerUuid.equalsIgnoreCase(configuredUuid)) return true;
            // UUID configured: name may no longer match unless fallback allowed.
            return policies.allowNameFallback && !configuredName.isEmpty()
                    && canon(name).equals(configuredName);
        }
        return !configuredName.isEmpty() && canon(name).equals(configuredName);
    }

    /**
     * Record→player identity matching. A record bound to a UUID matches by UUID
     * only — names are not unique across account history and are spoofable on
     * offline-mode servers. The name fallback applies solely to legacy records
     * with no stored UUID.
     */
    public static boolean identityMatches(PlayerGateway player, String storedUuid, String storedName) {
        String uuid = player.uuid() == null ? "" : player.uuid().toString();
        if (storedUuid != null && !storedUuid.isEmpty()) {
            return !uuid.isEmpty() && storedUuid.equalsIgnoreCase(uuid);
        }
        return storedName != null && !storedName.isEmpty()
                && canon(storedName).equals(canon(player.name()));
    }

    @Override
    public boolean isCommissioner(PlayerGateway player) {
        return isCommissioner(player.name(), player.uuid());
    }

    @Override
    public PlayerGateway findPlayer(String nameOrUuid) {
        return ctx.server().findPlayer(nameOrUuid);
    }

    @Override
    public String setupHintFor(PlayerGateway player) {
        if (!isCommissioner(player)) return null;
        return com.dwurdy.straja.domain.model.SetupChecklist.nextStep(
                ctx.setup().read(), ctx.npcs().read(), NpcAdminService.ROLE_ORDER);
    }

    public PermissionLevel permissionLevel(PlayerGateway player, GuardState state) {
        if (isCommissioner(player)) return PermissionLevel.COMMISSIONER;
        Rank rank = Rank.of(state.rank);
        if (rank == Rank.INSPECTOR) return PermissionLevel.LIEUTENANT;
        if (rank.atLeast(Rank.STAGIAR)) return PermissionLevel.GUARD;
        return PermissionLevel.PUBLIC;
    }

    /** On-duty guard (rank at least Stagiar) — the jailer-assault exemption rule. */
    @Override
    public boolean isOnDutyGuard(PlayerGateway player) {
        GuardState state = state(player);
        return state.duty && state.rank >= Rank.STAGIAR.level();
    }

    /**
     * §4: bracketed rank prefix for display surfaces. Authorized members keep
     * it regardless of faction or duty state; civilians and former members
     * (fired/resigned) show none. Suspended members keep the prefix — the
     * suspension is itself a Straja status.
     */
    @Override
    public String rankPrefixFor(PlayerGateway player) {
        if (player == null) return null;
        GuardState state = state(player);
        if (state.fired || state.resigned) return null;
        if (isCommissioner(player)) return "[" + ctx.policies().comisarTitle + "]";
        if (state.rank < Rank.STAGIAR.level()) return null;
        return "[" + ctx.policies().rankName(state.rank) + "]";
    }

    @Override
    public boolean hasCapability(PlayerGateway player, Capability capability) {
        GuardState state = state(player);
        if (isCommissioner(player)) return true;
        Rank rank = Rank.of(state.rank);
        return !state.suspended && !state.fired && !state.resigned
                && Capability.allowed(rank, capability);
    }

    public Result requireLevel(PlayerGateway player, PermissionLevel required) {
        GuardState state = state(player);
        if (!permissionLevel(player, state).atLeast(required)) return Result.fail("forbidden");
        return Result.pass();
    }

    public Result requireCapability(PlayerGateway player, Capability capability) {
        return hasCapability(player, capability) ? Result.pass() : Result.fail("forbidden");
    }

    /** Whether the actor may promote/demote/suspend the target per rank rules. */
    public Result canManage(PlayerGateway actor, String targetName, GuardState target) {
        if (isCommissioner(actor)) return Result.pass();
        GuardState actorState = state(actor);
        Rank actorRank = Rank.of(actorState.rank);
        Rank targetRank = Rank.of(target.rank);
        if (actorRank != Rank.INSPECTOR) return Result.fail("forbidden");
        if (canon(actor.name()).equals(canon(targetName))) return Result.fail("self_forbidden");
        if (targetRank.atLeast(Rank.INSPECTOR)) return Result.fail("peer_forbidden");
        return Result.pass();
    }
}
