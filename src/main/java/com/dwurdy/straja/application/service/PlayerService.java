package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.PermissionLevel;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Result;
import java.util.UUID;
import com.dwurdy.straja.domain.model.PersonnelRecord;

/**
 * Identity, permission resolution and player-state access. This is the only
 * place that decides who is commissioner and what an actor may do; rank text,
 * NPC dialogue or item names can never grant authority.
 */
public class PlayerService implements com.dwurdy.straja.application.port.in.PlayerQueryUseCase {
    private final StrajaContext ctx;
    private PersonnelService v2Personnel;
    private AuthorizationService v2Authorization;

    public PlayerService(StrajaContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Installs the V2 authority facade at the composition root. Legacy unit
     * contexts intentionally leave it unset and retain their old behavior.
     */
    public void useV2Authority(PersonnelService personnel, AuthorizationService authorization) {
        this.v2Personnel = personnel;
        this.v2Authorization = authorization;
    }

    public GuardState state(PlayerGateway player) {
        GuardState state = ctx.players().read(player.uuid());
        applyV2Projection(player.uuid(), state);
        // §14: keep a display name on the record so the Comisar's roster can
        // name members even when they are offline.
        if (player.name() != null && !player.name().equals(state.lastKnownName)) {
            state.lastKnownName = player.name();
            ctx.players().write(player.uuid(), state);
        }
        return state;
    }

    @Override
    public GuardState readState(PlayerGateway player) {
        GuardState state = ctx.players().read(player.uuid());
        applyV2Projection(player.uuid(), state);
        return state;
    }

    public GuardState state(UUID uuid) {
        GuardState state = ctx.players().read(uuid);
        applyV2Projection(uuid, state);
        return state;
    }

    public void save(UUID uuid, GuardState state) {
        applyV2Projection(uuid, state);
        state.refreshLifecycle();
        ctx.players().write(uuid, state);
    }

    public static String canon(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    /** UUID wins over name; debug override wins in local/test mode only. */
    public boolean isCommissioner(String name, UUID uuid) {
        PersonnelRecord v2Record = v2Record(uuid);
        if (v2Record != null) {
            return v2Record.active()
                    && v2Record.hasAppointment(com.dwurdy.straja.domain.model.AppointmentType.COMMISSIONER,
                    ctx.clock().nowMillis());
        }
        return isConfiguredCommissioner(name, uuid);
    }

    /**
     * Legacy deployment identity used only by login migration/bootstrap.
     * Sensitive gameplay checks must use {@link #isCommissioner(PlayerGateway)}
     * after a V2 personnel record exists.
     */
    public boolean isConfiguredCommissioner(PlayerGateway player) {
        return player != null && isConfiguredCommissioner(player.name(), player.uuid());
    }

    private boolean isConfiguredCommissioner(String name, UUID uuid) {
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
        PersonnelRecord v2Record = v2Record(player == null ? null : player.uuid());
        if (v2Record != null) {
            if (!v2Record.active()) return PermissionLevel.PUBLIC;
            if (v2Record.careerGrade == com.dwurdy.straja.domain.model.CareerGrade.INSPECTOR)
                return PermissionLevel.LIEUTENANT;
            if (v2Record.careerGrade == com.dwurdy.straja.domain.model.CareerGrade.MILITARY_STAGIAR
                    || v2Record.careerGrade == com.dwurdy.straja.domain.model.CareerGrade.MILITARY_STRAJER
                    || v2Record.careerGrade == com.dwurdy.straja.domain.model.CareerGrade.MILITARY_SERGENT)
                return PermissionLevel.GUARD;
            return PermissionLevel.PUBLIC;
        }
        Rank rank = Rank.of(state.rank);
        if (rank == Rank.INSPECTOR) return PermissionLevel.LIEUTENANT;
        if (rank.atLeast(Rank.STAGIAR)) return PermissionLevel.GUARD;
        return PermissionLevel.PUBLIC;
    }

    /** On-duty guard (rank at least Stagiar) — the jailer-assault exemption rule. */
    @Override
    public boolean isOnDutyGuard(PlayerGateway player) {
        if (player == null) return false;
        GuardState state = state(player);
        return state != null && state.duty && hasCapability(player, Capability.ROUTINE_PATROL);
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
        PersonnelRecord v2Record = v2Record(player == null ? null : player.uuid());
        if (v2Record != null && v2Authorization != null && capability != null) {
            var context = com.dwurdy.straja.domain.model.AuthorizationContext.of(
                    player.uuid().toString(), capability.name());
            context.stationId = v2Record.homeStationId;
            return v2Authorization.allowed(context);
        }
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
        PersonnelRecord v2Actor = v2Record(actor == null ? null : actor.uuid());
        if (v2Actor != null && v2Authorization != null) {
            PlayerGateway targetPlayer = targetName == null ? null : ctx.server().findPlayer(targetName);
            String targetUuid = targetPlayer == null ? targetName : targetPlayer.uuid().toString();
            if (targetPlayer == null && actor != null && canon(actor.name()).equals(canon(targetName)))
                targetUuid = actor.uuid().toString();
            var context = com.dwurdy.straja.domain.model.AuthorizationContext.of(
                    actor.uuid().toString(), "AUTHORIZE_PERSONNEL");
            context.subjectUuid = targetUuid == null ? "" : targetUuid;
            context.beneficiaryUuid = context.subjectUuid;
            context.requiresIndependentApproval = true;
            var decision = v2Authorization.authorize(context);
            return decision.allowed ? Result.pass() : Result.fail(decision.reasonCode.name().toLowerCase());
        }
        if (isCommissioner(actor)) return Result.pass();
        GuardState actorState = state(actor);
        Rank actorRank = Rank.of(actorState.rank);
        Rank targetRank = Rank.of(target.rank);
        if (actorRank != Rank.INSPECTOR) return Result.fail("forbidden");
        if (canon(actor.name()).equals(canon(targetName))) return Result.fail("self_forbidden");
        if (targetRank.atLeast(Rank.INSPECTOR)) return Result.fail("peer_forbidden");
        return Result.pass();
    }

    private PersonnelRecord v2Record(UUID uuid) {
        if (v2Personnel == null || uuid == null) return null;
        return v2Personnel.find(uuid.toString());
    }

    /**
     * Compatibility projection used by V1 services during phased cutover.
     * It is intentionally derived on every read/write, so stale GuardState
     * flags cannot reopen a suspended or terminated V2 record.
     */
    private void applyV2Projection(UUID uuid, GuardState state) {
        PersonnelRecord record = v2Record(uuid);
        if (record == null || state == null) return;
        state.rank = record.careerGrade == null ? Rank.CIVIL.level() : switch (record.careerGrade) {
            case MILITARY_STAGIAR -> Rank.STAGIAR.level();
            case MILITARY_STRAJER -> Rank.GUARD.level();
            case MILITARY_SERGENT -> Rank.SERGENT.level();
            case INSPECTOR -> Rank.INSPECTOR.level();
            case PROFESSIONAL_STAGIAR_SPECIALIST, PROFESSIONAL_SPECIALIST -> Rank.CIVIL.level();
        };
        state.applicationState = record.membershipStatus == com.dwurdy.straja.domain.model.PersonnelStatus.AUTHORIZED_ACTIVE
                ? "AUTHORIZED" : state.applicationState;
        state.suspended = record.membershipStatus == com.dwurdy.straja.domain.model.PersonnelStatus.SUSPENDED;
        state.fired = record.membershipStatus == com.dwurdy.straja.domain.model.PersonnelStatus.TERMINATED;
        state.resigned = record.membershipStatus == com.dwurdy.straja.domain.model.PersonnelStatus.RESIGNED;
        if (state.suspended || state.fired || state.resigned) {
            state.duty = false;
            state.resignationPending = false;
        }
    }
}
