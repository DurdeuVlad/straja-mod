package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.adapter.in.npc.NpcInteractionService;
import com.dwurdy.straja.adapter.in.npc.NpcRoles;
import com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Set;

/**
 * Brigadier registration for /straja. This class only translates arguments to
 * application service calls; all rules live in the services.
 */
public final class StrajaCommands {
    private StrajaCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(root());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root() {
        var root = Commands.literal("straja");

        root.executes(StrajaCommands::help);
        root.then(Commands.literal("help").executes(StrajaCommands::help));
        // NPC clicks use a short-lived, player-bound token; this is not a
        // public gameplay-command alias and is intentionally absent from help.
        root.then(npcActionNode());
        root.then(Commands.literal("status").executes(c -> player(c, StrajaRuntime.get().guards()::showStatus)));
        root.then(Commands.literal("rules").executes(c -> player(c, StrajaRuntime.get().guards()::showRules)));
        root.then(Commands.literal("regulament").executes(c -> player(c, StrajaRuntime.get().guards()::showRules)));
        root.then(adminOnly(Commands.literal("backup").executes(StrajaCommands::backup)));

        // recruitment
        root.then(adminOnly(Commands.literal("invite")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> StrajaRuntime.get().guards().invite(actor(c), target(c, "player")) ? 1 : 0))));
        root.then(adminOnly(Commands.literal("recruit").executes(c -> player(c, StrajaRuntime.get().guards()::recruit))));
        root.then(adminOnly(Commands.literal("recrute").executes(c -> player(c, StrajaRuntime.get().guards()::recruit))));
        root.then(adminOnly(Commands.literal("quiz")
                .executes(c -> player(c, p -> StrajaRuntime.get().guards().quiz(p, null)))
                .then(Commands.argument("answer", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().guards()
                                .quiz(p, StringArgumentType.getString(c, "answer")))))));

        // duty
        root.then(adminOnly(Commands.literal("start").executes(c -> player(c, StrajaRuntime.get().guards()::startDuty))));
        root.then(adminOnly(Commands.literal("checkpoint")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().guards()
                                .checkpoint(p, StringArgumentType.getString(c, "id")))))));
        root.then(adminOnly(Commands.literal("stop").executes(c -> player(c, StrajaRuntime.get().guards()::stopDuty))));

        var special = adminOnly(Commands.literal("special"));
        special.then(Commands.literal("start").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { StrajaRuntime.get().guards().specialStart(actor(c), target(c, "player")); return 1; })));
        special.then(Commands.literal("resume").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { StrajaRuntime.get().guards().specialResume(actor(c), target(c, "player")); return 1; })));
        special.then(Commands.literal("complete").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { StrajaRuntime.get().guards().specialComplete(actor(c), target(c, "player")); return 1; })));
        root.then(special);

        // resignation
        var resign = adminOnly(Commands.literal("resign"));
        resign.executes(c -> player(c, p -> StrajaRuntime.get().guards().resign(p, null)));
        resign.then(Commands.argument("action", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().guards()
                        .resign(p, StringArgumentType.getString(c, "action")))));
        root.then(resign);
        var demisie = adminOnly(Commands.literal("demisie"));
        demisie.executes(c -> player(c, p -> StrajaRuntime.get().guards().resign(p, null)));
        demisie.then(Commands.argument("action", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().guards()
                        .resign(p, StringArgumentType.getString(c, "action")))));
        root.then(demisie);
        root.then(adminOnly(Commands.literal("rejoin").executes(c -> player(c, StrajaRuntime.get().guards()::rejoin))));

        // economy/equipment
        root.then(adminOnly(Commands.literal("salary").executes(c -> player(c, StrajaRuntime.get().guards()::salary))));
        root.then(adminOnly(Commands.literal("coins").executes(c -> player(c, StrajaRuntime.get().guards()::coins))));
        root.then(adminOnly(Commands.literal("food").executes(c -> player(c, StrajaRuntime.get().guards()::food))));
        root.then(adminOnly(Commands.literal("kit").executes(c -> player(c, StrajaRuntime.get().guards()::kit))));
        root.then(adminOnly(Commands.literal("regear").executes(c -> player(c, StrajaRuntime.get().guards()::requestRegear))));
        root.then(adminOnly(Commands.literal("approve-regear").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { StrajaRuntime.get().guards().approveRegear(actor(c), target(c, "player")); return 1; }))));

        // admin rank ops
        for (String op : new String[]{"promote", "demote", "suspend", "fire"}) {
            var command = Commands.literal(op)
                    .then(Commands.argument("player", EntityArgument.player())
                            .executes(c -> { adminRank(c, op); return 1; }));
            root.then(adminOnly(command));
        }
        var reinstate = Commands.literal("reinstate")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> StrajaRuntime.get().guards().reinstate(actor(c), target(c, "player")) ? 1 : 0));
        root.then(adminOnly(reinstate));
        root.then(adminOnly(Commands.literal("faction")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> StrajaRuntime.get().guards()
                                        .setNativeFactionFor(actor(c), target(c, "player"),
                                                StringArgumentType.getString(c, "name")) ? 1 : 0)))));

        // setup
        var setCheckpoint = Commands.literal("set-checkpoint")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().guards()
                                .setCheckpoint(p, StringArgumentType.getString(c, "id")))));
        root.then(adminOnly(setCheckpoint));
        var setMissionTime = Commands.literal("set-mission-time")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("minutes", IntegerArgumentType.integer())
                                .executes(c -> adminActor(c, p -> StrajaRuntime.get().guards()
                                        .setMissionTime(p, StringArgumentType.getString(c, "id"),
                                                IntegerArgumentType.getInteger(c, "minutes"))))));
        root.then(adminOnly(setMissionTime));
        var setLocation = Commands.literal("set-location")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().guards()
                                .setLocation(p, StringArgumentType.getString(c, "name")))));
        root.then(adminOnly(setLocation));
        var setup = Commands.literal("setup")
                .executes(c -> adminActor(c, StrajaRuntime.get().guards()::showSetup));
        setup.then(Commands.literal("here")
                .executes(c -> adminActor(c, StrajaRuntime.get().guards()::setupLocationsHere)));
        setup.then(Commands.literal("patrol")
                .executes(c -> adminActor(c, StrajaRuntime.get().guards()::setupPatrol)));
        setup.then(Commands.literal("npcs").executes(NpcCommands::spawnMissing));
        root.then(adminOnly(setup));

        // inbox communication
        for (String op : new String[]{"report", "message", "request"}) {
            var command = Commands.literal(op)
                    .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(c -> player(c, p -> inboxOp(c, p, op))));
            root.then(adminOnly(command));
        }
        root.then(adminOnly(Commands.literal("inbox").executes(StrajaCommands::inbox)));

        // missions
        root.then(missionNode());

        // custody / restraints
        root.then(cuffsNode());

        // prison
        root.then(prisonNode());

        // civic: fines, complaints, rooms, archive
        root.then(fineNode());
        root.then(complaintNode());
        root.then(roomNode());
        root.then(archiveNode());

        // migration from the legacy KubeJS world (console-usable, op-only)
        root.then(migrateNode());

        // npc admin
        root.then(NpcCommands.build());

        // debug + test (gated)
        root.then(DebugCommands.build());
        root.then(TestCommands.build());

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> npcActionNode() {
        return Commands.literal("npc-action")
                .then(Commands.argument("token", StringArgumentType.word())
                        .executes(StrajaCommands::npcAction));
    }

    /**
     * {@code /straja migrate <worldPath>} — reads kubejs_persistent_data.nbt and
     * playerdata/*.dat under the given world directory and merges them into the
     * native stores. Idempotent; reports per-store counts.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> migrateNode() {
        var node = adminOnly(Commands.literal("migrate"));
        node.then(Commands.argument("worldPath", StringArgumentType.greedyString())
                .executes(c -> {
                    var source = c.getSource();
                    var runtime = StrajaRuntime.get();
                    if (runtime == null) { source.sendFailure(Component.literal("Straja nu este pornită.")); return 0; }
                    java.nio.file.Path world = java.nio.file.Path.of(
                            StringArgumentType.getString(c, "worldPath"));
                    int totalErrors = 0;
                    try {
                        var serverFile = world.resolve("kubejs_persistent_data.nbt");
                        if (java.nio.file.Files.exists(serverFile)) {
                            var report = runtime.migration().migrateServer(
                                    com.dwurdy.straja.adapter.out.migration.KubeJsNbtReader.readStrings(serverFile));
                            report.lines().forEach(l -> source.sendSystemMessage(Component.literal(l)));
                            totalErrors += report.errors();
                        } else {
                            source.sendFailure(Component.literal("Lipsește " + serverFile));
                        }
                        var playerDir = world.resolve("playerdata");
                        if (java.nio.file.Files.isDirectory(playerDir)) {
                            try (var stream = java.nio.file.Files.list(playerDir)) {
                                for (var file : stream.filter(f -> f.toString().endsWith(".dat")).toList()) {
                                    try {
                                        java.util.UUID uuid = java.util.UUID.fromString(
                                                file.getFileName().toString().replace(".dat", ""));
                                        var report = runtime.migration().migratePlayer(uuid,
                                                com.dwurdy.straja.adapter.out.migration.KubeJsNbtReader.readStrings(file));
                                        report.lines().forEach(l -> source.sendSystemMessage(Component.literal(l)));
                                        totalErrors += report.errors();
                                    } catch (IllegalArgumentException skip) {
                                        // non-uuid .dat file — ignore
                                    }
                                }
                            }
                        }
                        source.sendSystemMessage(Component.literal(
                                totalErrors == 0 ? "Migrație completă." : "Migrație cu " + totalErrors + " erori — vezi audit."));
                        return totalErrors == 0 ? 1 : 0;
                    } catch (java.io.IOException e) {
                        source.sendFailure(Component.literal("Migrație eșuată: " + e.getMessage()));
                        return 0;
                    }
                }));
        return node;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> missionNode() {
        var node = adminOnly(Commands.literal("mission"));
        node.executes(c -> player(c, p -> StrajaRuntime.get().missions().list(p)));
        node.then(Commands.literal("list").executes(c -> player(c, p -> StrajaRuntime.get().missions().list(p))));
        node.then(Commands.literal("carnet").executes(c -> player(c, StrajaRuntime.get().missions()::giveCarnet)));

        // quick/secretary create
        node.then(Commands.literal("create")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("minutes", IntegerArgumentType.integer())
                                .then(Commands.argument("reward", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("objective", StringArgumentType.greedyString())
                                                .executes(c -> player(c, p -> {
                                                    var target = target(c, "player");
                                                    StrajaRuntime.get().missions().createMission(p, target,
                                                            IntegerArgumentType.getInteger(c, "minutes"),
                                                            StringArgumentType.getString(c, "objective"),
                                                            IntegerArgumentType.getInteger(c, "reward"));
                                                })))))));

        // carnet draft flow
        var draft = Commands.literal("draft");
        draft.then(Commands.literal("status").executes(c -> player(c, StrajaRuntime.get().missions()::draftStatus)));
        draft.then(Commands.literal("write")
                .then(Commands.argument("minutes", IntegerArgumentType.integer())
                        .then(Commands.argument("start", StringArgumentType.word())
                                .then(Commands.argument("reward", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("objective", StringArgumentType.greedyString())
                                                .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                                        .draftWrite(p, IntegerArgumentType.getInteger(c, "minutes"),
                                                                StringArgumentType.getString(c, "start"),
                                                                IntegerArgumentType.getInteger(c, "reward"),
                                                                StringArgumentType.getString(c, "objective")))))))));
        draft.then(Commands.literal("scope")
                .then(Commands.argument("rank", StringArgumentType.word())
                        .then(Commands.argument("max", IntegerArgumentType.integer(1))
                                .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                        .draftScope(p, StringArgumentType.getString(c, "rank"),
                                                IntegerArgumentType.getInteger(c, "max")))))));
        draft.then(Commands.literal("sign").executes(c -> player(c, StrajaRuntime.get().missions()::draftSign)));
        draft.then(Commands.literal("package").executes(c -> player(c, StrajaRuntime.get().missions()::draftPackage)));
        node.then(draft);

        node.then(Commands.literal("give")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                .give(p, target(c, "player"))))));
        node.then(Commands.literal("invite")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                        .invite(p, StringArgumentType.getString(c, "id"),
                                                target(c, "player")))))));

        node.then(Commands.literal("resend")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                .resendPackage(p, StringArgumentType.getString(c, "id"))))));

        // participant lifecycle — id, optional trailing argument
        for (String op : new String[]{"join", "accept", "decline", "fail", "report", "complete",
                "claim", "recover"}) {
            var action = Commands.argument("id", StringArgumentType.word())
                    .executes(c -> player(c, p -> missionOp(c, p, op, null)));
            var builder = Commands.literal(op).then(action);
            if (Set.of("fail", "report").contains(op)) {
                builder = Commands.literal(op).then(action)
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(c -> player(c, p -> missionOp(c, p, op,
                                        StringArgumentType.getString(c, "reason")))));
            }
            node.then(builder);
        }
        node.then(Commands.literal("help").executes(StrajaCommands::missionHelp));
        return node;
    }

    private static void missionOp(CommandContext<CommandSourceStack> ctx, PlayerGateway player,
                                  String op, String extra) {
        var missions = StrajaRuntime.get().missions();
        String id = StringArgumentType.getString(ctx, "id");
        switch (op) {
            case "join" -> missions.join(player, id);
            case "accept" -> missions.accept(player, id);
            case "decline" -> missions.decline(player, id);
            case "fail" -> missions.fail(player, id, extra);
            case "report" -> missions.report(player, id, extra);
            case "complete" -> missions.complete(player, id);
            case "claim" -> missions.claimReward(player, id);
            case "recover" -> missions.recoverReward(player, id);
            default -> {}
        }
    }

    private static int missionHelp(CommandContext<CommandSourceStack> ctx) {
        String[] lines = {
                "/straja mission [list] | carnet | create <jucător> <min> <reward> <obiectiv>",
                "/straja mission draft write <min> <start|acum> <reward> <obiectiv> | scope <rank> <max> | status | sign | package",
                "/straja mission give <jucător> | invite <id> <jucător> | resend <id>",
                "/straja mission join|accept|decline|fail|report|complete|claim|recover <id> [text]"
        };
        for (String line : lines) ctx.getSource().sendSystemMessage(Component.literal(line));
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> cuffsNode() {
        var node = adminOnly(Commands.literal("cuffs"));
        node.executes(c -> player(c, p -> StrajaRuntime.get().custody().cuffStatus(p)));
        node.then(Commands.literal("status").executes(c -> player(c, StrajaRuntime.get().custody()::cuffStatus)));
        node.then(Commands.literal("downed").executes(c -> player(c, StrajaRuntime.get().custody()::downedStatus)));
        node.then(Commands.literal("item").executes(c -> player(c, StrajaRuntime.get().custody()::giveCuffs)));
        node.then(Commands.literal("request")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> player(c, p -> StrajaRuntime.get().custody()
                                .requestCuffs(p, target(c, "player"))))));
        node.then(Commands.literal("surrender")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> player(c, p -> StrajaRuntime.get().custody()
                                .requestSurrender(p, target(c, "player"))))));
        node.then(Commands.literal("release")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> player(c, p -> StrajaRuntime.get().custody()
                                .release(p, target(c, "player"))))));
        node.then(Commands.literal("emergency")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> player(c, p -> StrajaRuntime.get().custody()
                                .emergencyRelease(p, target(c, "player"))))));
        node.then(Commands.literal("sack-remove")
                .executes(c -> player(c, StrajaRuntime.get().custody()::removeHeadSack)));
        for (String op : new String[]{"accept", "refuse"}) {
            node.then(Commands.literal(op)
                    .then(Commands.argument("id", StringArgumentType.word())
                            .executes(c -> player(c, p -> {
                                var custody = StrajaRuntime.get().custody();
                                if ("accept".equals(op)) custody.accept(p, StringArgumentType.getString(c, "id"));
                                else custody.refuse(p, StringArgumentType.getString(c, "id"));
                            }))));
        }
        node.then(Commands.literal("help").executes(ctx -> {
            String[] lines = {
                    "/straja cuffs item | status | downed | request <jucător> | surrender <jucător>",
                    "/straja cuffs accept <id> | refuse <id> | release <jucător> | sack-remove | emergency <jucător>"
            };
            for (String line : lines) ctx.getSource().sendSystemMessage(Component.literal(line));
            return 1;
        }));
        return node;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> prisonNode() {
        var node = adminOnly(Commands.literal("prison"));
        node.executes(c -> player(c, p -> StrajaRuntime.get().prison().status(p)));
        node.then(Commands.literal("status").executes(c -> player(c, StrajaRuntime.get().prison()::status)));
        node.then(Commands.literal("cells").executes(c -> player(c, StrajaRuntime.get().prison()::listCells)));
        node.then(Commands.literal("arrest")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("days", IntegerArgumentType.integer(1))
                                .executes(c -> {
                                    var actor = actor(c);
                                    var runtime = StrajaRuntime.get();
                                    if (!runtime.players().isCommissioner(actor)
                                            && !runtime.players().hasCapability(actor,
                                                    com.dwurdy.straja.domain.model.Capability.EXECUTE_ARRESTS)) {
                                        c.getSource().sendFailure(Component.literal(
                                                "Arestarea cere rangul de Străjer sau Comisaru'."));
                                        return 0;
                                    }
                                    var t = target(c, "player");
                                    if (t == null) {
                                        c.getSource().sendFailure(Component.literal("Jucător offline."));
                                        return 0;
                                    }
                                    var s = runtime.prison().arrest(t, null,
                                            IntegerArgumentType.getInteger(c, "days"), actor, null);
                                    if (s == null) return 0;
                                    c.getSource().sendSystemMessage(Component.literal(
                                            "Sentință " + s.id + " (" + s.status + ")."));
                                    return 1;
                                }))));
        node.then(Commands.literal("release")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> {
                            var actor = actor(c);
                            var runtime = StrajaRuntime.get();
                            var t = target(c, "player");
                            if (t == null) {
                                c.getSource().sendFailure(Component.literal("Jucător offline."));
                                return 0;
                            }
                            return runtime.prison().release(actor, t, "command") ? 1 : 0;
                        })));
        node.then(Commands.literal("cell")
                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("minX", IntegerArgumentType.integer())
                                        .then(Commands.argument("minY", IntegerArgumentType.integer())
                                                .then(Commands.argument("minZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxY", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                                                                .executes(c -> player(c, p -> StrajaRuntime.get().prison()
                                                                                        .createCell(p,
                                                                                                StringArgumentType.getString(c, "id"),
                                                                                                p.dimension(),
                                                                                                IntegerArgumentType.getInteger(c, "minX"),
                                                                                                IntegerArgumentType.getInteger(c, "minY"),
                                                                                                IntegerArgumentType.getInteger(c, "minZ"),
                                                                                                IntegerArgumentType.getInteger(c, "maxX"),
                                                                                                IntegerArgumentType.getInteger(c, "maxY"),
                                                                                                IntegerArgumentType.getInteger(c, "maxZ")))))))))))));
        return node;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> fineNode() {
        var node = adminOnly(Commands.literal("fine"));
        node.then(Commands.literal("help").executes(ctx -> {
            String[] lines = {
                    "/straja fine book | write <jucător> <sumă> <lege> <descriere> | draft | issue <jucător>",
                    "/straja fine pay <F-id> | appeal <F-id> <motiv> | list | cancel <F-id>",
                    "/straja fine appeals | review <F-id> <uphold|reduce|void> [tarif] [motiv] | recover <F-id> <paid|retry>",
                    "/straja fine tasks | accept <task> | complete <task> | refuse <task> | arrest <task> [zile] | warrant <jucător> <motiv>"
            };
            for (String line : lines) ctx.getSource().sendSystemMessage(Component.literal(line));
            return 1;
        }));
        node.then(Commands.literal("book").executes(c -> player(c, p -> {
            if (!StrajaRuntime.get().players().hasCapability(p, com.dwurdy.straja.domain.model.Capability.ISSUE_FINES)) {
                p.tell("Registrul de Amenzi este disponibil doar Străjerilor activi.");
                return;
            }
            p.give(com.dwurdy.straja.domain.model.ItemSpec.of("straja:fine_book", 1));
            p.tell("Ai primit Registrul de Amenzi. Este reutilizabil și nu se consumă.");
        })));
        node.then(Commands.literal("write")
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .then(Commands.argument("law", StringArgumentType.word())
                                        .then(Commands.argument("description", StringArgumentType.greedyString())
                                                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                                        .writeDraft(p, find(c, "player"),
                                                                IntegerArgumentType.getInteger(c, "amount"),
                                                                StringArgumentType.getString(c, "law"),
                                                                StringArgumentType.getString(c, "description")))))))));
        node.then(Commands.literal("draft").executes(c -> player(c,
                p -> p.tell(StrajaRuntime.get().fines().draftText(p)))));
        node.then(Commands.literal("issue").then(Commands.argument("player", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().fines().issueFromDraft(p, find(c, "player"))))));
        node.then(Commands.literal("pay").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                        .pay(p, StringArgumentType.getString(c, "id"))))));
        node.then(Commands.literal("appeal").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                .appeal(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "reason")))))));
        node.then(Commands.literal("appeals").executes(c -> player(c, StrajaRuntime.get().fines()::listAppeals)));
        node.then(Commands.literal("review").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("decision", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                .reviewAppeal(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "decision"), null, null)))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                        .reviewAppeal(p, StringArgumentType.getString(c, "id"),
                                                StringArgumentType.getString(c, "decision"),
                                                IntegerArgumentType.getInteger(c, "amount"), null)))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                                .reviewAppeal(p, StringArgumentType.getString(c, "id"),
                                                        StringArgumentType.getString(c, "decision"),
                                                        IntegerArgumentType.getInteger(c, "amount"),
                                                        StringArgumentType.getString(c, "reason")))))))));
        node.then(Commands.literal("recover").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("decision", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                .recoverPayment(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "decision")))))));
        node.then(Commands.literal("list").executes(c -> player(c, StrajaRuntime.get().fines()::listFines)));
        node.then(Commands.literal("tasks").executes(c -> player(c, StrajaRuntime.get().fines()::listTasks)));
        node.then(Commands.literal("accept").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                        .acceptTask(p, StringArgumentType.getString(c, "id"))))));
        node.then(Commands.literal("complete").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                        .completeTask(p, StringArgumentType.getString(c, "id"))))));
        node.then(Commands.literal("refuse").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                        .refusePayment(p, StringArgumentType.getString(c, "id"))))));
        node.then(Commands.literal("arrest").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                        .arrest(p, StringArgumentType.getString(c, "id"), null)))
                .then(Commands.argument("days", IntegerArgumentType.integer(1))
                        .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                .arrest(p, StringArgumentType.getString(c, "id"),
                                        IntegerArgumentType.getInteger(c, "days")))))));
        node.then(Commands.literal("warrant").then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                                .issueHearingWarrant(p, find(c, "player"),
                                        StringArgumentType.getString(c, "reason")))))));
        node.then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().fines()
                        .cancelFine(p, StringArgumentType.getString(c, "id"))))));
        return node;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> complaintNode() {
        var node = adminOnly(Commands.literal("complaint"));
        node.then(Commands.literal("submit")
                .then(Commands.argument("accused", StringArgumentType.word())
                        .then(Commands.argument("category", StringArgumentType.word())
                                .then(Commands.argument("description", StringArgumentType.greedyString())
                                        .executes(c -> player(c, p -> StrajaRuntime.get().complaints()
                                                .submit(p, StringArgumentType.getString(c, "accused"),
                                                        StringArgumentType.getString(c, "category"),
                                                        StringArgumentType.getString(c, "description"))))))));
        node.then(Commands.literal("list").executes(c -> player(c, StrajaRuntime.get().complaints()::list)));
        for (String op : new String[]{"claim", "join", "leave"}) {
            node.then(Commands.literal(op).then(Commands.argument("id", StringArgumentType.word())
                    .executes(c -> player(c, p -> {
                        var complaints = StrajaRuntime.get().complaints();
                        String id = StringArgumentType.getString(c, "id");
                        switch (op) {
                            case "claim" -> complaints.claim(p, id);
                            case "join" -> complaints.join(p, id);
                            default -> complaints.leave(p, id);
                        }
                    }))));
        }
        node.then(Commands.literal("mobilize").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().complaints()
                                .mobilize(p, StringArgumentType.getString(c, "id"), find(c, "player")))))));
        node.then(Commands.literal("report").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().complaints()
                                .report(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "text")))))));
        node.then(Commands.literal("confirm").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().complaints()
                        .complainantDecision(p, StringArgumentType.getString(c, "id"), "confirm", null)))));
        node.then(Commands.literal("withdraw").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().complaints()
                                .complainantDecision(p, StringArgumentType.getString(c, "id"), "withdraw",
                                        StringArgumentType.getString(c, "reason")))))));
        node.then(Commands.literal("review").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("decision", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().complaints()
                                .review(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "decision"), null)))
                        .then(Commands.argument("reward", IntegerArgumentType.integer(0))
                                .executes(c -> player(c, p -> StrajaRuntime.get().complaints()
                                        .review(p, StringArgumentType.getString(c, "id"),
                                                StringArgumentType.getString(c, "decision"),
                                                IntegerArgumentType.getInteger(c, "reward"))))))));
        return node;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> roomNode() {
        var node = adminOnly(Commands.literal("room"));
        node.executes(c -> player(c, p -> StrajaRuntime.get().rooms().status(p)));
        node.then(Commands.literal("status").executes(c -> player(c, StrajaRuntime.get().rooms()::status)));
        node.then(Commands.literal("list").executes(c -> player(c, StrajaRuntime.get().rooms()::list)));
        node.then(Commands.literal("create")
                .executes(c -> player(c, p -> StrajaRuntime.get().rooms().discover(p, null)))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().rooms()
                                .discover(p, StringArgumentType.getString(c, "id"))))));
        node.then(Commands.literal("assign").executes(c -> player(c, p -> {
            String result = StrajaRuntime.get().rooms().assignAutomatically(p);
            p.tell(switch (result.split(":")[0]) {
                case "ASSIGNED" -> "Camera " + result.substring(9) + " ți-a fost atribuită.";
                case "WAITING" -> "Nu există camere libere. Ești pe poziția " + result.substring(8) + ".";
                case "ALREADY_ASSIGNED" -> "Ai deja o cameră atribuită.";
                case "NOT_ELIGIBLE" -> "Nu ești eligibil pentru o cameră.";
                default -> "Nu există camere configurate.";
            });
        })));
        node.then(Commands.literal("release").executes(c -> player(c, p -> {
            p.tell(StrajaRuntime.get().rooms().releaseFor(p)
                    ? "Camera a fost eliberată." : "Nu aveai o cameră atribuită.");
        })));
        return node;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> archiveNode() {
        var node = adminOnly(Commands.literal("archive"));
        node.then(Commands.literal("role").then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("enabled", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .grantArchivist(p, find(c, "player"),
                                        List.of("on", "true", "da", "yes").contains(
                                                StringArgumentType.getString(c, "enabled").toLowerCase())))))));
        node.then(Commands.literal("list").executes(c -> player(c, StrajaRuntime.get().archive()::listFolders)));
        var folder = Commands.literal("folder");
        folder.then(Commands.literal("create")
                .then(Commands.argument("title", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .createFolder(p, StringArgumentType.getString(c, "title"), null)))));
        folder.then(Commands.literal("read").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .readFolder(p, StringArgumentType.getString(c, "id"))))));
        folder.then(Commands.literal("close").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .folderStatus(p, StringArgumentType.getString(c, "id"), "CLOSED")))));
        folder.then(Commands.literal("open").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .folderStatus(p, StringArgumentType.getString(c, "id"), "OPEN")))));
        node.then(folder);
        var sheet = Commands.literal("sheet");
        sheet.then(Commands.literal("new").then(Commands.argument("folder", StringArgumentType.word())
                .then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                        .newSheet(p, StringArgumentType.getString(c, "folder"),
                                                StringArgumentType.getString(c, "type"),
                                                StringArgumentType.getString(c, "title"))))))));
        sheet.then(Commands.literal("edit").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("content", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .editSheet(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "content")))))));
        sheet.then(Commands.literal("recipients").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("names", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .setRecipients(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "names")))))));
        sheet.then(Commands.literal("submit").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .submitSheet(p, StringArgumentType.getString(c, "id"))))));
        sheet.then(Commands.literal("read").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .readSheet(p, StringArgumentType.getString(c, "id"))))));
        sheet.then(Commands.literal("list").then(Commands.argument("folder", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .listSheets(p, StringArgumentType.getString(c, "folder"))))));
        sheet.then(Commands.literal("sign").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .signSheet(p, StringArgumentType.getString(c, "id"), null)))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .signSheet(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "reason")))))));
        sheet.then(Commands.literal("revoke").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .revokeSheet(p, StringArgumentType.getString(c, "id"))))));
        sheet.then(Commands.literal("copy").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .copySheet(p, StringArgumentType.getString(c, "id"),
                                        IntegerArgumentType.getInteger(c, "count"), null)))
                        .then(Commands.argument("targets", StringArgumentType.greedyString())
                                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                        .copySheet(p, StringArgumentType.getString(c, "id"),
                                                IntegerArgumentType.getInteger(c, "count"),
                                                StringArgumentType.getString(c, "targets"))))))));
        sheet.then(Commands.literal("pack").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("target", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .packEnvelope(p, StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "target")))))));
        node.then(sheet);
        var catalog = Commands.literal("catalog");
        catalog.then(Commands.literal("add").then(Commands.argument("folder", StringArgumentType.word())
                .then(Commands.argument("alias", StringArgumentType.greedyString())
                        .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                                .catalogAdd(p, StringArgumentType.getString(c, "folder"),
                                        StringArgumentType.getString(c, "alias")))))));
        catalog.then(Commands.literal("list").then(Commands.argument("folder", StringArgumentType.word())
                .executes(c -> player(c, p -> StrajaRuntime.get().archive()
                        .catalogList(p, StringArgumentType.getString(c, "folder"))))));
        node.then(catalog);
        return node;
    }

    /** Resolves a player by name/uuid through the query port (includes test virtuals). */
    private static PlayerGateway find(CommandContext<CommandSourceStack> ctx, String name) {
        String wanted = StringArgumentType.getString(ctx, name);
        return StrajaRuntime.get().playerQueries().findPlayer(wanted);
    }

    // ------------------------------------------------------------ plumbing

    private static StrajaRuntime runtime(CommandSourceStack source) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) source.sendFailure(Component.literal("Straja runtime is not running."));
        return runtime;
    }

    private static PlayerGateway actor(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        return player != null
                ? new MinecraftPlayerGateway(ctx.getSource().getServer(), player.getUUID())
                : new ConsolePlayerGateway(ctx.getSource());
    }

    private static PlayerGateway target(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, name);
            return new MinecraftPlayerGateway(ctx.getSource().getServer(), player.getUUID());
        } catch (CommandSyntaxException error) {
            return null;
        }
    }

    private static int player(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<PlayerGateway> op) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Această comandă necesită un jucător."));
            return 0;
        }
        op.accept(new MinecraftPlayerGateway(ctx.getSource().getServer(), player.getUUID()));
        return 1;
    }

    private static int npcAction(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Această acțiune necesită un jucător."));
            return 0;
        }
        String actionId = NpcInteractionService.consumeActionToken(
                StringArgumentType.getString(ctx, "token"), player.getUUID());
        boolean handled = actionId != null
                && NpcRoles.performAction(actionId, player, player.serverLevel());
        if (!handled) {
            ctx.getSource().sendFailure(Component.literal("Acțiunea NPC a expirat sau nu este validă."));
        }
        return handled ? 1 : 0;
    }

    private static void adminRank(CommandContext<CommandSourceStack> ctx, String op) {
        var guards = StrajaRuntime.get().guards();
        var actor = actor(ctx);
        var target = target(ctx, "player");
        if (target == null) { ctx.getSource().sendFailure(Component.literal("Jucător offline sau necunoscut.")); return; }
        switch (op) {
            case "promote" -> guards.promote(actor, target);
            case "demote" -> guards.demote(actor, target);
            case "suspend" -> guards.suspend(actor, target);
            case "fire" -> guards.fire(actor, target);
            default -> {}
        }
    }

    private static void inboxOp(CommandContext<CommandSourceStack> ctx, PlayerGateway player, String op) {
        String text = StringArgumentType.getString(ctx, "text");
        var guards = StrajaRuntime.get().guards();
        switch (op) {
            case "report" -> guards.report(player, text);
            case "message" -> guards.message(player, text);
            case "request" -> guards.request(player, text);
            default -> {}
        }
    }

    private static int inbox(CommandContext<CommandSourceStack> ctx) {
        StrajaRuntime runtime = runtime(ctx.getSource());
        if (runtime == null) return 0;
        var actor = actor(ctx);
        if (!runtime.players().isCommissioner(actor)) {
            ctx.getSource().sendFailure(Component.literal("Doar Comisaru' poate citi inbox-ul."));
            return 0;
        }
        var messages = runtime.context().inbox().read();
        if (messages.isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.literal("Inbox gol."));
            return 1;
        }
        int shown = 0;
        for (int i = messages.size() - 1; i >= 0 && shown < 20; i--, shown++) {
            var message = messages.get(i);
            ctx.getSource().sendSystemMessage(Component.literal(
                    "[" + message.type + "] " + message.sender + ": " + message.text));
        }
        return 1;
    }

    static List<String> helpLines(boolean admin) {
        return CommandPolicy.helpLines(admin);
    }

    /** Shared typed-command policy: every non-public root is permission level 2. */
    static boolean isAdminOnly(String command) {
        return CommandPolicy.isAdminOnly(command);
    }

    static LiteralArgumentBuilder<CommandSourceStack> adminOnly(
            LiteralArgumentBuilder<CommandSourceStack> node) {
        if (!isAdminOnly(node.getLiteral())) {
            throw new IllegalArgumentException("Unclassified admin command: " + node.getLiteral());
        }
        return node.requires(StrajaCommands::hasAdminPermission);
    }

    private static boolean hasAdminPermission(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        for (String line : helpLines(ctx.getSource().hasPermission(2))) {
            ctx.getSource().sendSystemMessage(Component.literal(line));
        }
        return 1;
    }

    private static int backup(CommandContext<CommandSourceStack> ctx) {
        try {
            var result = StrajaDataProvider.createBackup(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Backup Straja creat: " + result.id() + " (" + result.storeCount()
                            + " store-uri, " + result.retainedSnapshotCount() + " snapshot-uri păstrate)."), true);
            return 1;
        } catch (RuntimeException error) {
            ctx.getSource().sendFailure(Component.literal("Backup Straja eșuat: " + error.getMessage()));
            return 0;
        }
    }

    private static int adminActor(CommandContext<CommandSourceStack> ctx,
                                  java.util.function.Consumer<PlayerGateway> op) {
        op.accept(actor(ctx));
        return 1;
    }

    /** Pure policy manifest kept separate so command-surface tests need no MC runtime. */
    static final class CommandPolicy {
        private static final Set<String> ADMIN_ONLY_COMMANDS = Set.of(
                // recruitment and guard lifecycle
                "invite", "recruit", "recrute", "quiz", "start", "checkpoint", "stop", "special",
                "resign", "demisie", "rejoin",
                // economy and communication
                "salary", "coins", "food", "kit", "regear", "approve-regear",
                "report", "message", "request", "inbox",
                // roleplay service lanes
                "mission", "cuffs", "prison", "fine", "complaint", "room", "archive",
                // typed setup and administrator operations
                "promote", "demote", "suspend", "fire", "reinstate", "faction",
                "set-checkpoint", "set-mission-time", "set-location", "setup",
                "migrate", "backup", "npc", "debug", "test");

        private CommandPolicy() {}

        static boolean isAdminOnly(String command) {
            return ADMIN_ONLY_COMMANDS.contains(command);
        }

        static List<String> helpLines(boolean admin) {
            if (!admin) {
                return List.of("/straja status | rules | regulament | help");
            }
            return List.of(
                    "/straja status | rules | regulament | help",
                    "/straja invite <jucător> | recruit | quiz <răspuns> | resign | rejoin",
                    "/straja start | checkpoint <id> | stop | special <start|resume|complete> <jucător>",
                    "/straja salary | coins | food | kit | regear | approve-regear <jucător>",
                    "/straja report|message|request <text> | inbox",
                    "/straja promote | demote | suspend | reinstate | fire | faction <jucător> <nume>",
                    "/straja setup — checklist ghidat | setup here | setup patrol | setup npcs",
                    "/straja set-checkpoint <id> | set-mission-time <id> <min> | set-location <nume>",
                    "/straja mission | cuffs | prison | fine | complaint | room | archive — help pe subcomandă",
                    "/straja npc list|spawn|assign|set-name|set-skin|remove",
                    "/straja migrate <worldPath> | backup | debug ... | test ...");
        }
    }
}
