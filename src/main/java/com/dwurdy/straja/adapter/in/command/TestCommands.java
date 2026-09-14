package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.adapter.in.test.VirtualPlayerGateway;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /straja test * — deterministic, console-first test surface. Hard-gated by
 * testing.enableTestCommands (local environments only) and permission level 2.
 * Virtual players are synthetic {@link PlayerGateway} implementations, so the
 * same application services and permission checks run as for real players.
 */
final class TestCommands {
    private TestCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        // Keep permission in the advertised tree and retain the runtime's
        // configured/local gates in gated() for direct or stale-client calls.
        var test = StrajaCommands.adminOnly(Commands.literal("test"));

        test.then(Commands.literal("create-player")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            String name = StringArgumentType.getString(ctx, "name");
                            var player = runtime.testPlayers().create(name);
                            send(ctx, "virtual player " + name + " uuid=" + player.uuid());
                            return 1;
                        })));

        test.then(Commands.literal("remove-player")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            boolean removed = runtime.testPlayers().remove(StringArgumentType.getString(ctx, "name"));
                            send(ctx, removed ? "removed" : "unknown virtual player");
                            return removed ? 1 : 0;
                        })));

        test.then(Commands.literal("list-players")
                .executes(ctx -> {
                    var runtime = gated(ctx);
                    if (runtime == null) return 0;
                    var players = runtime.testPlayers().all();
                    if (players.isEmpty()) { send(ctx, "no virtual players"); return 1; }
                    for (var p : players) {
                        send(ctx, p.name() + " uuid=" + p.uuid() + " pos=" + fmt(p.x(), p.y(), p.z()));
                    }
                    return 1;
                }));

        test.then(Commands.literal("reset")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            var player = player(ctx, runtime);
                            if (player == null) return 0;
                            runtime.players().save(player.uuid(), new GuardState());
                            send(ctx, "state reset for " + player.name());
                            return 1;
                        })));

        test.then(Commands.literal("reset-all")
                .executes(ctx -> {
                    var runtime = gated(ctx);
                    if (runtime == null) return 0;
                    for (var p : runtime.testPlayers().all()) {
                        runtime.players().save(p.uuid(), new GuardState());
                        p.clearLog();
                    }
                    send(ctx, "all virtual player state reset");
                    return 1;
                }));

        test.then(Commands.literal("set-rank")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("rank", IntegerArgumentType.integer(0, 4))
                                .executes(ctx -> {
                                    var runtime = gated(ctx);
                                    if (runtime == null) return 0;
                                    if (!runtime.policies().debugAllowGrantRank) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "Granting ranks via the test surface is disabled (debug.allowGrantRank=false)."));
                                        return 0;
                                    }
                                    var player = player(ctx, runtime);
                                    if (player == null) return 0;
                                    GuardState state = runtime.players().state(player.uuid());
                                    state.rank = IntegerArgumentType.getInteger(ctx, "rank");
                                    state.invited = true;
                                    state.quizPassed = state.rank >= 1;
                                    runtime.players().save(player.uuid(), state);
                                    send(ctx, "rank=" + state.rank + " pentru " + player.name());
                                    return 1;
                                }))));

        test.then(Commands.literal("give")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("item",
                                        net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            var runtime = gated(ctx);
                                            if (runtime == null) return 0;
                                            var player = player(ctx, runtime);
                                            if (player == null) return 0;
                                            var spec = ItemSpec.of(
                                                    net.minecraft.commands.arguments.ResourceLocationArgument
                                                            .getId(ctx, "item").toString(),
                                                    IntegerArgumentType.getInteger(ctx, "count"));
                                            if (!player.giveVerified(spec)) {
                                                ctx.getSource().sendFailure(Component.literal("no room"));
                                                return 0;
                                            }
                                            send(ctx, "gave " + spec.count() + "x " + spec.id() + " to " + player.name());
                                            return 1;
                                        })))));

        test.then(Commands.literal("move")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> {
                                                    var runtime = gated(ctx);
                                                    if (runtime == null) return 0;
                                                    var player = virtual(ctx, runtime);
                                                    if (player == null) return 0;
                                                    player.moveTo(DoubleArgumentType.getDouble(ctx, "x"),
                                                            DoubleArgumentType.getDouble(ctx, "y"),
                                                            DoubleArgumentType.getDouble(ctx, "z"));
                                                    send(ctx, player.name() + " moved");
                                                    return 1;
                                                }))))));

        test.then(Commands.literal("teleport")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> {
                                                    var runtime = gated(ctx);
                                                    if (runtime == null) return 0;
                                                    var player = player(ctx, runtime);
                                                    if (player == null) return 0;
                                                    player.teleport("minecraft:overworld",
                                                            DoubleArgumentType.getDouble(ctx, "x"),
                                                            DoubleArgumentType.getDouble(ctx, "y"),
                                                            DoubleArgumentType.getDouble(ctx, "z"));
                                                    send(ctx, player.name() + " teleported");
                                                    return 1;
                                                }))))));

        // Duty lifecycle driven through the real GuardService.
        test.then(Commands.literal("start-duty")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().startDuty(player(ctx, runtime))))));
        test.then(Commands.literal("stop-duty")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().stopDuty(player(ctx, runtime))))));
        test.then(Commands.literal("checkpoint")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("cp", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.guards().checkpoint(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "cp")))))));

        test.then(Commands.literal("recruit")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().recruit(player(ctx, runtime))))));
        test.then(Commands.literal("apply")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().applyForStraja(player(ctx, runtime))))));
        test.then(Commands.literal("faction")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "faction=" + runtime.guards().declareNativeFaction(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "name"))))))));
        test.then(Commands.literal("specialization")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .then(Commands.argument("op", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                        "specialization=" + runtime.guards().setSpecialization(
                                                                player(ctx, runtime),
                                                                playerArg(ctx, runtime, "target"),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                "add".equalsIgnoreCase(StringArgumentType
                                                                        .getString(ctx, "op")))))))))));
        test.then(Commands.literal("quiz")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("answer", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.guards().quiz(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "answer")))))));
        test.then(Commands.literal("set-blocks")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    var runtime = gated(ctx);
                                    if (runtime == null) return 0;
                                    var player = player(ctx, runtime);
                                    if (player == null) return 0;
                                    GuardState state = runtime.players().state(player.uuid());
                                    state.serviceBlocks = IntegerArgumentType.getInteger(ctx, "blocks");
                                    runtime.players().save(player.uuid(), state);
                                    send(ctx, "serviceBlocks=" + state.serviceBlocks + " pentru " + player.name());
                                    return 1;
                                }))));
        test.then(Commands.literal("training-progress")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().showProgress(player(ctx, runtime))))));
        test.then(Commands.literal("training-promote")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().requestPromotion(player(ctx, runtime))))));
        test.then(Commands.literal("training-manual")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().giveManual(player(ctx, runtime))))));
        test.then(Commands.literal("salary")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().salary(player(ctx, runtime))))));
        test.then(Commands.literal("food")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().food(player(ctx, runtime))))));
        test.then(Commands.literal("kit")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().kit(player(ctx, runtime))))));
        test.then(Commands.literal("resign")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("action", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.guards().resign(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "action")))))));
        test.then(Commands.literal("rejoin")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().rejoin(player(ctx, runtime))))));

        test.then(Commands.literal("advance-time")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            long seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                            runtime.clock().advance(seconds * 1000L);
                            // Immediately tick virtual players so accrual applies now.
                            for (var p : runtime.testPlayers().all()) {
                                runtime.guards().tickPlayerDuty(p);
                            }
                            send(ctx, "clock advanced by " + seconds + "s");
                            return 1;
                        })));

        test.then(Commands.literal("dump-state")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            var player = player(ctx, runtime);
                            if (player == null) return 0;
                            GuardState state = runtime.players().state(player.uuid());
                            send(ctx, "uuid=" + player.uuid()
                                    + " rank=" + state.rank
                                    + " invited=" + state.invited
                                    + " quizPassed=" + state.quizPassed
                                    + " duty=" + state.duty
                                    + " mode=" + state.mode
                                    + " patrol=" + state.patrolState
                                    + " blocks=" + state.serviceBlocks
                                    + " salary=" + state.unpaidSalary
                                    + " debt=" + state.equipmentDebt
                                    + " suspended=" + state.suspended
                                    + " fired=" + state.fired
                                    + " resigned=" + state.resigned);
                            return 1;
                        })));

        test.then(Commands.literal("inventory")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            var player = virtual(ctx, runtime);
                            if (player == null) return 0;
                            var contents = player.virtualInventory().contents();
                            if (contents.isEmpty()) { send(ctx, "inventory empty"); return 1; }
                            for (var stack : contents) {
                                send(ctx, stack.count() + "x " + stack.id()
                                        + (stack.customData().isEmpty() ? "" : " " + stack.customData()));
                            }
                            return 1;
                        })));

        test.then(Commands.literal("tell-log")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            var player = virtual(ctx, runtime);
                            if (player == null) return 0;
                            var log = player.messageLog();
                            if (log.isEmpty()) { send(ctx, "no messages"); return 1; }
                            int shown = 0;
                            for (int i = log.size() - 1; i >= 0 && shown < 20; i--, shown++) {
                                send(ctx, log.get(i));
                            }
                            return 1;
                        })));

        // Setup: delegate to the real commissioner-gated service calls with
        // the virtual player acting through the debug-commissioner override.
        test.then(Commands.literal("set-commissioner")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = gated(ctx);
                            if (runtime == null) return 0;
                            var player = player(ctx, runtime);
                            if (player == null) return 0;
                            var store = runtime.context().test().read();
                            store.debugCommissionerUuid = player.uuid().toString();
                            runtime.context().test().write(store);
                            send(ctx, "debug commissioner = " + player.name());
                            return 1;
                        })));

        test.then(Commands.literal("set-checkpoint")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("cp", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.guards().setCheckpoint(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "cp")))))));

        test.then(Commands.literal("set-mission-time")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("cp", StringArgumentType.word())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer())
                                        .executes(ctx -> run(ctx, runtime ->
                                                runtime.guards().setMissionTime(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "cp"),
                                                        IntegerArgumentType.getInteger(ctx, "minutes"))))))));

        test.then(Commands.literal("set-location")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.guards().setLocation(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "name")))))));

        test.then(Commands.literal("setup")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().showSetup(player(ctx, runtime))))));

        test.then(Commands.literal("setup-here")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().setupLocationsHere(player(ctx, runtime))))));

        test.then(Commands.literal("setup-patrol")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.guards().setupPatrol(player(ctx, runtime))))));

        test.then(Commands.literal("policy-list")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.policyConfig().list(player(ctx, runtime))))));

        test.then(Commands.literal("policy-get")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.policyConfig().get(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "key")))))));

        test.then(Commands.literal("policy-set")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, runtime ->
                                                runtime.policyConfig().set(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value"))))))));

        test.then(Commands.literal("policy-reset")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.policyConfig().reset(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "key")))))));

        // missions
        test.then(Commands.literal("select-slot")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                .executes(ctx -> run(ctx, runtime -> {
                                    var p = virtual(ctx, runtime);
                                    if (p != null) p.selectSlot(IntegerArgumentType.getInteger(ctx, "slot"));
                                })))));

        test.then(Commands.literal("mission-create")
                .then(Commands.argument("issuer", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer())
                                        .then(Commands.argument("reward", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("objective", StringArgumentType.greedyString())
                                                        .executes(ctx -> run(ctx, runtime ->
                                                                runtime.missions().createMission(
                                                                        playerArg(ctx, runtime, "issuer"),
                                                                        resolveVirtual(ctx, runtime, "target"),
                                                                        IntegerArgumentType.getInteger(ctx, "minutes"),
                                                                        StringArgumentType.getString(ctx, "objective"),
                                                                        IntegerArgumentType.getInteger(ctx, "reward"))))))))));

        test.then(Commands.literal("mission-give")
                .then(Commands.argument("issuer", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> {
                                    var ok = runtime.missions().give(playerArg(ctx, runtime, "issuer"),
                                            resolveVirtual(ctx, runtime, "target"));
                                    send(ctx, "give=" + ok);
                                })))));

        test.then(Commands.literal("mission-issue")
                .then(Commands.argument("issuer", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "issue=" + runtime.missionRoleplay().issueDraft(
                                                playerArg(ctx, runtime, "issuer"),
                                                resolveVirtual(ctx, runtime, "target"))))))));
        test.then(Commands.literal("mission-invite")
                .then(Commands.argument("issuer", StringArgumentType.word())
                        .then(Commands.argument("mid", StringArgumentType.word())
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, runtime ->
                                                runtime.missions().invite(playerArg(ctx, runtime, "issuer"),
                                                        StringArgumentType.getString(ctx, "mid"),
                                                        resolveVirtual(ctx, runtime, "target"))))))));

        test.then(Commands.literal("mission-draft")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("action", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> {
                                    var p = player(ctx, runtime);
                                    var m = runtime.missions();
                                    switch (StringArgumentType.getString(ctx, "action")) {
                                        case "status" -> m.draftStatus(p);
                                        case "sign" -> m.draftSign(p);
                                        case "package" -> m.draftPackage(p);
                                        default -> send(ctx, "unknown draft action");
                                    }
                                }))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer())
                                        .then(Commands.argument("start", StringArgumentType.word())
                                                .then(Commands.argument("reward", IntegerArgumentType.integer(0))
                                                        .then(Commands.argument("objective", StringArgumentType.greedyString())
                                                                .executes(ctx -> run(ctx, runtime ->
                                                                        runtime.missions().draftWrite(player(ctx, runtime),
                                                                                IntegerArgumentType.getInteger(ctx, "minutes"),
                                                                                StringArgumentType.getString(ctx, "start"),
                                                                                IntegerArgumentType.getInteger(ctx, "reward"),
                                                                                StringArgumentType.getString(ctx, "objective")))))))))));

        for (String op : new String[]{"mission-join", "mission-accept", "mission-decline",
                "mission-claim", "mission-recover"}) {
            test.then(Commands.literal(op)
                    .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("mid", StringArgumentType.word())
                                    .executes(ctx -> run(ctx, runtime -> {
                                        var m = runtime.missions();
                                        var p = player(ctx, runtime);
                                        var mid = StringArgumentType.getString(ctx, "mid");
                                        switch (op) {
                                            case "mission-join" -> m.join(p, mid);
                                            case "mission-accept" -> m.accept(p, mid);
                                            case "mission-decline" -> m.decline(p, mid);
                                            case "mission-claim" -> m.claimReward(p, mid);
                                            case "mission-recover" -> m.recoverReward(p, mid);
                                            default -> {}
                                        }
                                    })))));
        }
        test.then(Commands.literal("mission-report")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("mid", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, runtime ->
                                                runtime.missions().report(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "mid"),
                                                        StringArgumentType.getString(ctx, "text"))))))));
        test.then(Commands.literal("mission-fail")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("mid", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, runtime ->
                                                runtime.missions().fail(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "mid"),
                                                        StringArgumentType.getString(ctx, "text"))))))));
        test.then(Commands.literal("mission-complete")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("mid", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.missions().complete(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "mid")))))));
        test.then(Commands.literal("mission-list")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.missions().list(player(ctx, runtime))))));
        test.then(Commands.literal("mission-dump")
                .executes(ctx -> run(ctx, runtime -> {
                    var store = runtime.context().missions().read();
                    for (var m : store.missions) {
                        send(ctx, runtime.missions().summary(m));
                    }
                    send(ctx, "missions=" + store.missions.size()
                            + " budgets=" + store.rewardBudgets);
                })));

        // custody / restraints
        test.then(Commands.literal("cuffs-item")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.custody().giveCuffs(player(ctx, runtime))))));
        test.then(Commands.literal("cuffs-request")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().requestCuffs(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target")))))));
        test.then(Commands.literal("surrender-request")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().requestSurrender(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target")))))));
        test.then(Commands.literal("cuffs-accept")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("req", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().accept(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "req")))))));
        test.then(Commands.literal("cuffs-refuse")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("req", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().refuse(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "req")))))));
        test.then(Commands.literal("cuffs-release")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().release(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target")))))));
        test.then(Commands.literal("cuffs-emergency")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().emergencyRelease(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target")))))));
        test.then(Commands.literal("rope")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().applyRope(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target")))))));
        test.then(Commands.literal("head-sack")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.custody().applyHeadSack(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target")))))));
        test.then(Commands.literal("sack-remove")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.custody().removeHeadSack(player(ctx, runtime))))));
        test.then(Commands.literal("baton-strike")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .then(Commands.argument("damage", IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx, runtime -> {
                                            var issuer = player(ctx, runtime);
                                            var tgt = resolveVirtual(ctx, runtime, "target");
                                            if (issuer == null || tgt == null) return;
                                            var outcome = runtime.custody().batonStrike(issuer, tgt,
                                                    tgt.health(), tgt.absorption(),
                                                    IntegerArgumentType.getInteger(ctx, "damage"));
                                            if (outcome.action()
                                                    == com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase
                                                            .DamageAction.ALLOW_NONLETHAL) {
                                                double capped = runtime.custody().capBatonDamage(
                                                        tgt.health(), tgt.absorption());
                                                tgt.setHealth(Math.max(1, tgt.health() - Math.min(capped,
                                                        IntegerArgumentType.getInteger(ctx, "damage"))));
                                            }
                                            send(ctx, "baton=" + outcome.action() + " " + outcome.reason());
                                        }))))));
        test.then(Commands.literal("custody-dump")
                .executes(ctx -> run(ctx, runtime -> {
                    var s = runtime.context().custody().read();
                    send(ctx, "requests=" + s.cuffRequests.keySet()
                            + " cuffed=" + s.cuffed.keySet()
                            + " bound=" + s.bound.keySet()
                            + " sacks=" + s.headSacks.keySet()
                            + " downed=" + s.downed.keySet()
                            + " pendingKeys=" + s.pendingKeys);
                })));

        // prison
        test.then(Commands.literal("cell-create")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("cell", StringArgumentType.word())
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                .executes(ctx -> run(ctx, runtime -> {
                                                                                    var p = player(ctx, runtime);
                                                                                    if (p == null) return;
                                                                                    runtime.prison().createCell(p,
                                                                                            StringArgumentType.getString(ctx, "cell"),
                                                                                            p.dimension(),
                                                                                            IntegerArgumentType.getInteger(ctx, "x1"),
                                                                                            IntegerArgumentType.getInteger(ctx, "y1"),
                                                                                            IntegerArgumentType.getInteger(ctx, "z1"),
                                                                                            IntegerArgumentType.getInteger(ctx, "x2"),
                                                                                            IntegerArgumentType.getInteger(ctx, "y2"),
                                                                                            IntegerArgumentType.getInteger(ctx, "z2"));
                                                                                })))))))))));
        test.then(Commands.literal("prison-arrest")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .then(Commands.argument("days", IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx, runtime -> {
                                            var s = runtime.prison().arrest(
                                                    resolveVirtual(ctx, runtime, "target"), null,
                                                    IntegerArgumentType.getInteger(ctx, "days"),
                                                    player(ctx, runtime), null);
                                            send(ctx, s == null ? "arrest=refused"
                                                    : "sentence=" + s.id + " status=" + s.status
                                                    + " cell=" + s.cellId + " remainingMs=" + s.remainingActiveMs);
                                        }))))));
        test.then(Commands.literal("prison-release")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime ->
                                        runtime.prison().release(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target"), "test"))))));
        test.then(Commands.literal("prison-status")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.prison().status(player(ctx, runtime))))));
        test.then(Commands.literal("prison-tick")
                .executes(ctx -> run(ctx, runtime -> {
                    runtime.prison().tick();
                    send(ctx, "prison ticked");
                })));
        test.then(Commands.literal("prison-dump")
                .executes(ctx -> run(ctx, runtime -> {
                    var s = runtime.context().prison().read();
                    for (var sentence : s.sentences) {
                        send(ctx, sentence.id + " " + sentence.target + " " + sentence.status
                                + " cell=" + sentence.cellId + " remainingMs=" + sentence.remainingActiveMs);
                    }
                    send(ctx, "cells=" + s.cells.size() + " assignments=" + s.assignments.keySet()
                            + " waitlist=" + s.waitlist.size());
                })));

        // ---------------------------------------------------------------- fines
        test.then(Commands.literal("fine-write")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("law", StringArgumentType.word())
                                                .then(Commands.argument("desc", StringArgumentType.greedyString())
                                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                                "write=" + runtime.fines().writeDraft(player(ctx, runtime),
                                                                        resolveVirtual(ctx, runtime, "target"),
                                                                        IntegerArgumentType.getInteger(ctx, "amount"),
                                                                        StringArgumentType.getString(ctx, "law"),
                                                                        StringArgumentType.getString(ctx, "desc")))))))))));
        test.then(Commands.literal("fine-issue")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "issue=" + runtime.fines().issueFromDraft(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target"))))))));
        test.then(Commands.literal("fine-pay")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("fine", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "pay=" + runtime.fines().pay(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "fine"))))))));
        test.then(Commands.literal("fine-appeal")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("fine", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                "appeal=" + runtime.fines().appeal(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "fine"),
                                                        StringArgumentType.getString(ctx, "reason")))))))));
        test.then(Commands.literal("fine-review")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("fine", StringArgumentType.word())
                                .then(Commands.argument("decision", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                "review=" + runtime.fines().reviewAppeal(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "fine"),
                                                        StringArgumentType.getString(ctx, "decision"), null, null))))))));
        test.then(Commands.literal("fine-accept-task")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("task", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "accept=" + runtime.fines().acceptTask(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "task"))))))));
        test.then(Commands.literal("fine-complete-task")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("task", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "complete=" + runtime.fines().completeTask(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "task"))))))));
        test.then(Commands.literal("fine-refuse")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("task", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "refuse=" + runtime.fines().refusePayment(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "task"))))))));
        test.then(Commands.literal("fine-arrest")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("task", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "arrest=" + runtime.fines().arrest(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "task"), null)))))));
        test.then(Commands.literal("fine-tick")
                .executes(ctx -> run(ctx, runtime -> {
                    runtime.fines().tick();
                    send(ctx, "fines ticked");
                })));
        /** Test-only: shrinks the online grace of a fine so escalation can be exercised live. */
        test.then(Commands.literal("fine-set-grace")
                .then(Commands.argument("fine", StringArgumentType.word())
                        .then(Commands.argument("ms", IntegerArgumentType.integer(0))
                                .executes(ctx -> run(ctx, runtime -> {
                                    var store = runtime.context().fines().read();
                                    var fine = store.fines.stream()
                                            .filter(f -> f.id.equalsIgnoreCase(StringArgumentType.getString(ctx, "fine")))
                                            .findFirst().orElse(null);
                                    if (fine == null) { send(ctx, "fine not found"); return; }
                                    fine.onlineGraceMs = IntegerArgumentType.getInteger(ctx, "ms");
                                    runtime.context().fines().write(store);
                                    send(ctx, "grace=" + fine.onlineGraceMs);
                                })))));
        test.then(Commands.literal("fine-dump")
                .executes(ctx -> run(ctx, runtime -> {
                    var s = runtime.context().fines().read();
                    for (var f : s.fines) {
                        send(ctx, f.id + " " + f.target + " " + f.amount + " " + f.status
                                + " graceMs=" + f.onlineGraceMs + " elapsedMs=" + f.onlineElapsedMs
                                + " task=" + f.taskId);
                    }
                    for (var t : s.tasks) {
                        send(ctx, "task " + t.id + " " + t.kind + " " + t.status + " target=" + t.target
                                + " assignees=" + t.assignees);
                    }
                    send(ctx, "fines=" + s.fines.size() + " tasks=" + s.tasks.size());
                })));

        // ---------------------------------------------------------------- complaints
        test.then(Commands.literal("report-submit")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "submit=" + runtime.reportRoleplay().submit(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "text"),
                                                "", "", "")))))));
        test.then(Commands.literal("report-status")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.reportRoleplay().status(player(ctx, runtime))))));
        test.then(Commands.literal("report-list")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime ->
                                runtime.reportRoleplay().listForReview(player(ctx, runtime))))));
        test.then(Commands.literal("report-review")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("report", StringArgumentType.word())
                                .then(Commands.argument("decision", StringArgumentType.word())
                                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                        "review=" + runtime.reportRoleplay().review(
                                                                player(ctx, runtime),
                                                                StringArgumentType.getString(ctx, "report"),
                                                                StringArgumentType.getString(ctx, "decision"),
                                                                StringArgumentType.getString(ctx, "note"))))))))));
        test.then(Commands.literal("complaint-submit")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("accused", StringArgumentType.word())
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                        "submit=" + runtime.complaints().submit(player(ctx, runtime),
                                                                StringArgumentType.getString(ctx, "accused"),
                                                                StringArgumentType.getString(ctx, "category"),
                                                                StringArgumentType.getString(ctx, "text"))))))))));
        test.then(Commands.literal("complaint-claim")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("complaint", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "claim=" + runtime.complaints().claim(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "complaint"))))))));
        test.then(Commands.literal("complaint-report")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("complaint", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                "report=" + runtime.complaints().report(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "complaint"),
                                                        StringArgumentType.getString(ctx, "text")))))))));
        test.then(Commands.literal("complaint-decide")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("complaint", StringArgumentType.word())
                                .then(Commands.argument("decision", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                "decide=" + runtime.complaints().complainantDecision(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "complaint"),
                                                        StringArgumentType.getString(ctx, "decision"), null))))))));
        test.then(Commands.literal("complaint-review")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("complaint", StringArgumentType.word())
                                .then(Commands.argument("decision", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                "review=" + runtime.complaints().review(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "complaint"),
                                                        StringArgumentType.getString(ctx, "decision"), null))))))));
        test.then(Commands.literal("complaint-dump")
                .executes(ctx -> run(ctx, runtime -> {
                    var s = runtime.context().complaints().read();
                    for (var c : s.complaints) {
                        send(ctx, c.id + " " + c.complainant + " vs " + c.accused + " " + c.status
                                + " lead=" + c.lead + " reward=" + c.rewardStatus
                                + " claims=" + c.rewardClaims.keySet());
                    }
                    send(ctx, "complaints=" + s.complaints.size());
                })));

        // ---------------------------------------------------------------- rooms
        test.then(Commands.literal("room-discover")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("room", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> {
                                    var room = runtime.rooms().discover(player(ctx, runtime),
                                            StringArgumentType.getString(ctx, "room"));
                                    send(ctx, "room=" + (room == null ? "null" : room.id));
                                })))));
        test.then(Commands.literal("room-assign")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                "assign=" + runtime.rooms().assignAutomatically(player(ctx, runtime)))))));
        test.then(Commands.literal("room-release")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                "release=" + runtime.rooms().releaseFor(player(ctx, runtime)))))));
        test.then(Commands.literal("room-waitlist")
                .executes(ctx -> run(ctx, runtime -> {
                    runtime.rooms().processWaitlist();
                    send(ctx, "waitlist processed");
                })));
        test.then(Commands.literal("room-dump")
                .executes(ctx -> run(ctx, runtime -> {
                    var s = runtime.context().rooms().read();
                    for (var r : s.rooms) {
                        var owner = s.assignments.get(r.id);
                        send(ctx, r.id + " door=" + r.hasDoor + " owner="
                                + (owner == null ? "-" : owner.player) + " sign=" + r.signStatus);
                    }
                    for (var w : s.waitlist) send(ctx, "wait " + w.player + " @" + w.queuedAt);
                    send(ctx, "rooms=" + s.rooms.size() + " waitlist=" + s.waitlist.size());
                })));

        // ---------------------------------------------------------------- archive
        test.then(Commands.literal("archive-grant")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "grant=" + runtime.archive().grantArchivist(player(ctx, runtime),
                                                resolveVirtual(ctx, runtime, "target"), true)))))));
        test.then(Commands.literal("archive-folder")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("title", StringArgumentType.word())
                                .then(Commands.argument("dept", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                "folder=" + runtime.archive().createFolder(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "title"),
                                                        StringArgumentType.getString(ctx, "dept")))))))));
        test.then(Commands.literal("archive-sheet")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("folder", StringArgumentType.word())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                        "sheet=" + runtime.archive().newSheet(player(ctx, runtime),
                                                                StringArgumentType.getString(ctx, "folder"),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "title"))))))))));
        test.then(Commands.literal("archive-edit")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("sheet", StringArgumentType.word())
                                .then(Commands.argument("content", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, runtime -> send(ctx,
                                                "edit=" + runtime.archive().editSheet(player(ctx, runtime),
                                                        StringArgumentType.getString(ctx, "sheet"),
                                                        StringArgumentType.getString(ctx, "content")))))))));
        test.then(Commands.literal("archive-submit")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("sheet", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "submit=" + runtime.archive().submitSheet(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "sheet"))))))));
        test.then(Commands.literal("archive-sign")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("sheet", StringArgumentType.word())
                                .executes(ctx -> run(ctx, runtime -> send(ctx,
                                        "sign=" + runtime.archive().signSheet(player(ctx, runtime),
                                                StringArgumentType.getString(ctx, "sheet"), "test")))))));
        test.then(Commands.literal("archive-dump")
                .executes(ctx -> run(ctx, runtime -> {
                    var s = runtime.context().archive().read();
                    for (var f : s.folders.values()) send(ctx, "folder " + f.id + " " + f.title + " " + f.status);
                    for (var sh : s.sheets.values()) {
                        send(ctx, "sheet " + sh.id + " " + sh.folderId + " " + sh.status
                                + " r" + sh.revision + " signed="
                                + (sh.signedBy == null ? "-" : sh.signedBy.uuid));
                    }
                    send(ctx, "folders=" + s.folders.size() + " sheets=" + s.sheets.size());
                })));

        test.then(Commands.literal("assert")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("field", StringArgumentType.word())
                                .then(Commands.argument("expected", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            var runtime = gated(ctx);
                                            if (runtime == null) return 0;
                                            var player = player(ctx, runtime);
                                            if (player == null) return 0;
                                            GuardState state = runtime.players().state(player.uuid());
                                            String field = StringArgumentType.getString(ctx, "field");
                                            String expected = StringArgumentType.getString(ctx, "expected");
                                            String actual = field(runtime, player, state, field);
                                            if (expected.equals(actual)) {
                                                send(ctx, "ASSERT PASS " + field + "=" + actual);
                                                return 1;
                                            }
                                            send(ctx, "ASSERT FAIL " + field + " expected=" + expected + " actual=" + actual);
                                            return 0;
                                        })))));

        return test;
    }

    private static String field(StrajaRuntime runtime, PlayerGateway player, GuardState state, String field) {
        return switch (field) {
            case "rank" -> String.valueOf(state.rank);
            case "duty" -> String.valueOf(state.duty);
            case "mode" -> state.mode;
            case "patrol" -> state.patrolState;
            case "salary" -> String.valueOf(state.unpaidSalary);
            case "debt" -> String.valueOf(state.equipmentDebt);
            case "lifecycle" -> state.lifecycle;
            case "suspended" -> String.valueOf(state.suspended);
            case "fired" -> String.valueOf(state.fired);
            case "resigned" -> String.valueOf(state.resigned);
            case "health" -> String.valueOf(player.health());
            case "coins" -> String.valueOf(runtime.context().currency().balanceOf(player));
            case "specializations" -> state.specializations == null ? "null"
                    : String.join(",", state.specializations);
            case "prefix" -> {
                String prefix = runtime.playerQueries().rankPrefixFor(player);
                yield prefix == null ? "none" : prefix;
            }
            default -> "?";
        };
    }

    @FunctionalInterface
    private interface Op {
        void run(StrajaRuntime runtime)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Op op)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var runtime = gated(ctx);
        if (runtime == null) return 0;
        op.run(runtime);
        return 1;
    }

    private static StrajaRuntime gated(CommandContext<CommandSourceStack> ctx) {
        var runtime = StrajaRuntime.get();
        // Test commands exist for local environments only — the flag can never
        // lift the surface outside local, regardless of configuration.
        if (runtime == null || !runtime.policies().testCommandsEnabled
                || !runtime.policies().isLocalEnvironment()
                || !ctx.getSource().hasPermission(2)) {
            ctx.getSource().sendFailure(Component.literal(
                    "Test commands disabled (testing.enableTestCommands, local environment only)."));
            return null;
        }
        return runtime;
    }

    /** Resolves an id to a virtual player first, then an online player by name. */
    private static PlayerGateway player(CommandContext<CommandSourceStack> ctx, StrajaRuntime runtime)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return playerArg(ctx, runtime, "id");
    }

    private static PlayerGateway playerArg(CommandContext<CommandSourceStack> ctx,
                                           StrajaRuntime runtime, String arg)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, arg);
        var virtual = runtime.testPlayers().get(id);
        if (virtual != null) return virtual;
        var online = ctx.getSource().getServer().getPlayerList().getPlayerByName(id);
        if (online != null) return new MinecraftPlayerGateway(ctx.getSource().getServer(), online.getUUID());
        throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                Component.literal("Unknown player '" + id + "'.")).create();
    }

    /** Resolves a named argument (virtual player first, then online). */
    private static PlayerGateway resolveVirtual(CommandContext<CommandSourceStack> ctx,
                                                StrajaRuntime runtime, String arg)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return playerArg(ctx, runtime, arg);
    }

    private static VirtualPlayerGateway virtual(CommandContext<CommandSourceStack> ctx, StrajaRuntime runtime) {
        var player = runtime.testPlayers().get(StringArgumentType.getString(ctx, "id"));
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown virtual player."));
        }
        return player;
    }

    private static String fmt(double x, double y, double z) {
        return String.format("%.1f,%.1f,%.1f", x, y, z);
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSystemMessage(Component.literal(text));
    }
}
