package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/**
 * /straja debug * — local-only diagnostics, gated by config. Outside the local
 * environment debug is disabled unless explicitly allowed by policy.
 */
final class DebugCommands {
    private DebugCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        var debug = Commands.literal("debug");

        debug.then(Commands.literal("readiness").executes(ctx -> {
            var runtime = StrajaRuntime.get();
            if (runtime == null || !debugAllowed(ctx.getSource(), runtime)) return deny(ctx);
            var p = runtime.policies();
            send(ctx, "--- RELEASE READINESS [" + p.environment + "] ---");
            send(ctx, (p.coinItemIds.size() == 4 && runtime.context().currency().available()
                    ? "PASS" : "FAIL") + " currency provider (configured coin items)");
            send(ctx, (runtime.context().delivery().available() ? "PASS" : "FAIL")
                    + " envelope provider (Envelope mail service)");
            send(ctx, (!p.commissionerUuid.isEmpty() || p.isLocalEnvironment() || !p.requireCommissionerUuidOutsideLocal
                    ? "PASS" : "FAIL") + " commissioner identity");
            send(ctx, (!p.debugEnabled || p.isLocalEnvironment() || !p.requireDebugDisabledOutsideLocal
                    ? "PASS" : "FAIL") + " debug disabled outside local");
            return 1;
        }));

        debug.then(Commands.literal("commissioner")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            var runtime = StrajaRuntime.get();
                            if (runtime == null || !debugAllowed(ctx.getSource(), runtime)) return deny(ctx);
                            var target = EntityArgument.getPlayer(ctx, "player");
                            var store = runtime.context().test().read();
                            store.debugCommissionerUuid = target.getStringUUID();
                            runtime.context().test().write(store);
                            send(ctx, "Debug commissioner set: " + target.getGameProfile().getName());
                            return 1;
                        })));

        debug.then(Commands.literal("audit")
                .executes(ctx -> {
                    var runtime = StrajaRuntime.get();
                    if (runtime == null || !debugAllowed(ctx.getSource(), runtime)) return deny(ctx);
                    var tail = runtime.audit().tail(20);
                    if (tail.isEmpty()) send(ctx, "Audit gol.");
                    for (var entry : tail) {
                        send(ctx, entry.action + " | " + entry.actor + " -> " + entry.target
                                + " | " + entry.result + " | " + entry.details);
                    }
                    return 1;
                }));

        debug.then(Commands.literal("quiz-answers")
                .executes(ctx -> {
                    var runtime = StrajaRuntime.get();
                    if (runtime == null || !debugAllowed(ctx.getSource(), runtime)) return deny(ctx);
                    if (!runtime.policies().debugRevealQuizAnswers) {
                        ctx.getSource().sendFailure(Component.literal("Revelarea răspunsurilor este dezactivată."));
                        return 0;
                    }
                    int index = 1;
                    for (var q : runtime.policies().quiz) {
                        send(ctx, "#" + index++ + " " + q.question() + " → " + String.join(" / ", q.answers()));
                    }
                    return 1;
                }));

        return debug;
    }

    private static boolean debugAllowed(CommandSourceStack source, StrajaRuntime runtime) {
        var p = runtime.policies();
        if (!p.debugEnabled) return false;
        if (p.debugLocalOnly && !p.isLocalEnvironment()) return false;
        // Deployment gate: outside local, debug stays off unless the operator
        // explicitly opts out of the gate — debugLocalOnly alone is not enough.
        if (!p.isLocalEnvironment() && p.requireDebugDisabledOutsideLocal) return false;
        return source.hasPermission(2);
    }

    private static int deny(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal("Debug este dezactivat sau interzis în acest mediu."));
        return 0;
    }

    private static void send(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSystemMessage(Component.literal(text));
    }
}
