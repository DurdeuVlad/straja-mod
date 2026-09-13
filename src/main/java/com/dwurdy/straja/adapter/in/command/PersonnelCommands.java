package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Text fallback/admin surface for the native NPC personnel workflow.
 * NPCs remain the ordinary gameplay entry points; these commands make reports,
 * audience requests and commissioner review usable before a dedicated client
 * screen is added.
 */
public final class PersonnelCommands {
    private PersonnelCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(root());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root() {
        var root = Commands.literal("straja-personnel");

        root.then(Commands.literal("status").executes(ctx -> {
            var runtime = StrajaRuntime.get();
            var actor = actor(ctx.getSource(), runtime);
            if (actor == null) return 0;
            var state = runtime.personnel().ensurePersonnelRecord(actor);
            actor.tell("Autorizație: " + (state.authorized() ? "VALIDĂ" : "NU")
                    + " | nr. serviciu: " + (state.serviceNumber == null ? "—" : state.serviceNumber)
                    + " | rang: " + com.dwurdy.straja.domain.model.Rank.of(state.rank).displayName()
                    + " | tură: " + (state.duty ? "DA" : "NU"));
            return 1;
        }));

        root.then(Commands.literal("report")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            var runtime = StrajaRuntime.get();
                            var actor = actor(ctx.getSource(), runtime);
                            if (actor == null) return 0;
                            return runtime.personnel().submitActivityReport(actor,
                                    StringArgumentType.getString(ctx, "text")) ? 1 : 0;
                        })));

        root.then(Commands.literal("audience")
                .executes(ctx -> {
                    var runtime = StrajaRuntime.get();
                    var actor = actor(ctx.getSource(), runtime);
                    if (actor == null) return 0;
                    return runtime.personnel().requestAudience(actor, null) ? 1 : 0;
                })
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            var runtime = StrajaRuntime.get();
                            var actor = actor(ctx.getSource(), runtime);
                            if (actor == null) return 0;
                            return runtime.personnel().requestAudience(actor,
                                    StringArgumentType.getString(ctx, "reason")) ? 1 : 0;
                        })));

        root.then(Commands.literal("authorize")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("rank", IntegerArgumentType.integer(1, 4))
                                .executes(ctx -> {
                                    var runtime = StrajaRuntime.get();
                                    var actor = actor(ctx.getSource(), runtime);
                                    if (actor == null) return 0;
                                    var target = EntityArgument.getPlayer(ctx, "player");
                                    var targetGateway = new MinecraftPlayerGateway(
                                            target.getServer(), target.getUUID());
                                    return runtime.personnel().authorizeExperienced(actor, targetGateway,
                                            IntegerArgumentType.getInteger(ctx, "rank")) ? 1 : 0;
                                }))));

        root.then(Commands.literal("revoke")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            var runtime = StrajaRuntime.get();
                            var actor = actor(ctx.getSource(), runtime);
                            if (actor == null) return 0;
                            var target = EntityArgument.getPlayer(ctx, "player");
                            var targetGateway = new MinecraftPlayerGateway(
                                    target.getServer(), target.getUUID());
                            return runtime.personnel().revokeAuthorization(actor, targetGateway) ? 1 : 0;
                        })));

        root.then(Commands.literal("inbox").executes(ctx -> {
            var runtime = StrajaRuntime.get();
            var actor = actor(ctx.getSource(), runtime);
            if (actor == null) return 0;
            if (!runtime.players().isCommissioner(actor)) {
                actor.tell("Doar Comisaru' poate consulta registrul Secretariatului.");
                return 0;
            }
            var entries = runtime.personnel().pendingPersonnelInbox(actor);
            if (entries.isEmpty()) actor.tell("Nu există rapoarte sau audiențe în așteptare.");
            for (var entry : entries) {
                actor.tell(entry.id + " | " + entry.type + " | " + entry.sender + " | " + entry.text);
            }
            return entries.size();
        }));

        var review = Commands.literal("review")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("decision", StringArgumentType.word())
                                .executes(ctx -> review(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "decision"), null))
                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                        .executes(ctx -> review(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "decision"),
                                                StringArgumentType.getString(ctx, "note"))))));
        root.then(review);

        root.then(Commands.literal("resolve-audience")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = StrajaRuntime.get();
                            var actor = actor(ctx.getSource(), runtime);
                            if (actor == null) return 0;
                            return runtime.personnel().resolveAudience(actor,
                                    StringArgumentType.getString(ctx, "id")) ? 1 : 0;
                        })));

        return root;
    }

    private static int review(CommandSourceStack source, String id, String decision, String note) {
        var runtime = StrajaRuntime.get();
        var actor = actor(source, runtime);
        if (actor == null) return 0;
        return runtime.personnel().reviewActivityReport(actor, id, decision, note) ? 1 : 0;
    }

    private static MinecraftPlayerGateway actor(CommandSourceStack source, StrajaRuntime runtime) {
        if (runtime == null) return null;
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Această acțiune necesită un jucător."));
            return null;
        }
        return new MinecraftPlayerGateway(source.getServer(), player.getUUID());
    }
}
