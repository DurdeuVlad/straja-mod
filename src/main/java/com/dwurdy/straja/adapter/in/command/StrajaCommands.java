package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.adapter.in.npc.NpcInteractionService;
import com.dwurdy.straja.adapter.in.npc.NpcRoles;
import com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
        AdminCommandHelp.attach(dispatcher);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root() {
        var root = Commands.literal("straja");

        // NPC clicks use a short-lived, player-bound token; this is not a
        // public gameplay-command alias and is intentionally absent from help.
        root.then(npcActionNode());
        root.then(Commands.literal("status").executes(c -> player(c, StrajaRuntime.get().guards()::showStatus)));
        root.then(Commands.literal("rules").executes(c -> player(c, StrajaRuntime.get().guards()::showRules)));
        root.then(Commands.literal("regulament").executes(c -> player(c, StrajaRuntime.get().guards()::showRules)));
        root.then(adminOnly(Commands.literal("backup").executes(StrajaCommands::backup)));
        root.then(v2PersonnelCommands());
        root.then(v2StationCommands());
        root.then(v2DoctorCommands());
        root.then(v2OutboxCommands());
        root.then(v2PromotionCommands());
        root.then(v2DocumentCommands());
        root.then(v2EquipmentCommands());
        root.then(v2MobilizationCommands());
        root.then(v2CampaignCommands());
        root.then(v2SettlementCommands());

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
        // §7: player-facing — free-duty ranks stop at will; patrol guards are
        // directed back to the Secretary inside the use case.
        root.then(Commands.literal("stop").executes(c -> player(c, StrajaRuntime.get().guards()::stopDuty)));

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

        // merit ledger: players inspect; the Comisar docks requisition points
        var merit = adminOnly(Commands.literal("merit"));
        merit.executes(c -> player(c, StrajaRuntime.get().guards()::showMerit));
        merit.then(Commands.literal("dock")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("points", IntegerArgumentType.integer(1))
                                .executes(c -> StrajaRuntime.get().guards()
                                        .meritDock(actor(c), target(c, "player"),
                                                IntegerArgumentType.getInteger(c, "points")) ? 1 : 0))));
        root.then(merit);

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
        root.then(adminOnly(Commands.literal("specialization")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> StrajaRuntime.get().guards()
                                                .setSpecialization(actor(c), target(c, "player"),
                                                        StringArgumentType.getString(c, "name"), true) ? 1 : 0)))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> StrajaRuntime.get().guards()
                                                .setSpecialization(actor(c), target(c, "player"),
                                                        StringArgumentType.getString(c, "name"), false) ? 1 : 0))))));

        // setup
        var setCheckpoint = Commands.literal("set-checkpoint")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().guards()
                                .setCheckpoint(p, StringArgumentType.getString(c, "id")))));
        root.then(adminOnly(setCheckpoint));
        var checkpoint = Commands.literal("checkpoint")
                .then(Commands.literal("add")
                        .requires(source -> source.hasPermission(CommandPermissions.SETUP))
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().guards().addCheckpoint(p))))
                .then(Commands.literal("remove")
                        .requires(source -> source.hasPermission(CommandPermissions.SETUP))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(c -> adminActor(c, p -> StrajaRuntime.get().guards()
                                        .removeCheckpoint(p, StringArgumentType.getString(c, "id"))))));
        root.then(adminOnly(checkpoint));
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
        // Admin tool kit: Comisar/op holder gate re-checked inside the service.
        setup.then(Commands.literal("tools")
                .executes(c -> adminActor(c, StrajaRuntime.get().adminTools()::giveToolKit)));
        root.then(adminOnly(setup));

        // runtime policy overrides (persisted YAML layer, live apply)
        var policy = Commands.literal("policy");
        policy.then(Commands.literal("list")
                .executes(c -> adminActor(c, StrajaRuntime.get().policyConfig()::list)));
        policy.then(Commands.literal("get")
                .then(Commands.argument("key", StringArgumentType.word())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().policyConfig()
                                .get(p, StringArgumentType.getString(c, "key"))))));
        policy.then(Commands.literal("set")
                .then(Commands.argument("key", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(c -> adminActor(c, p -> StrajaRuntime.get().policyConfig()
                                        .set(p, StringArgumentType.getString(c, "key"),
                                                StringArgumentType.getString(c, "value")))))));
        policy.then(Commands.literal("reset")
                .then(Commands.argument("key", StringArgumentType.word())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().policyConfig()
                                .reset(p, StringArgumentType.getString(c, "key"))))));
        root.then(adminOnly(policy));

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
        root.then(identityCardNode());

        // migration from the legacy KubeJS world (console-usable, OP 4 only)
        root.then(migrateNode());

        // §25 emergency system — usable by the Comisar and op/console; the
        // use case performs the authority check.
        root.then(emergencyNode());

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
        draft.then(Commands.literal("from")
                .then(Commands.argument("template", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                .draftFromTemplate(p, StringArgumentType.getString(c, "template"))))));
        draft.then(Commands.literal("adjust")
                .then(Commands.argument("hours", StringArgumentType.word())
                        .then(Commands.argument("risk", StringArgumentType.word())
                                .then(Commands.argument("reward", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                                        .draftAdjust(p, StringArgumentType.getString(c, "hours"),
                                                                StringArgumentType.getString(c, "risk"),
                                                                StringArgumentType.getString(c, "reward"),
                                                                StringArgumentType.getString(c, "reason"))))
                                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                                .draftAdjust(p, StringArgumentType.getString(c, "hours"),
                                                        StringArgumentType.getString(c, "risk"),
                                                        StringArgumentType.getString(c, "reward"), ""))))))));
        node.then(draft);

        // §13 templates — issuer preview + commissioner administration
        node.then(Commands.literal("templates")
                .executes(c -> player(c, StrajaRuntime.get().missions()::templateList)));
        var template = Commands.literal("template");
        template.then(Commands.literal("list")
                .executes(c -> player(c, StrajaRuntime.get().missions()::templateListAll)));
        template.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("minrank", IntegerArgumentType.integer(1, 4))
                                .then(Commands.argument("hours", DoubleArgumentType.doubleArg(0.0, 24.0))
                                        .then(Commands.argument("risk", DoubleArgumentType.doubleArg(0.0, 10.0))
                                                .then(Commands.argument("maxpaid", IntegerArgumentType.integer(1))
                                                        .then(Commands.argument("deadline", IntegerArgumentType.integer(1))
                                                                .then(Commands.argument("patrol", BoolArgumentType.bool())
                                                                        .then(Commands.argument("objective", StringArgumentType.greedyString())
                                                                                .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                                                                        .templateCreate(p,
                                                                                                StringArgumentType.getString(c, "name"),
                                                                                                IntegerArgumentType.getInteger(c, "minrank"),
                                                                                                DoubleArgumentType.getDouble(c, "hours"),
                                                                                                DoubleArgumentType.getDouble(c, "risk"),
                                                                                                IntegerArgumentType.getInteger(c, "maxpaid"),
                                                                                                IntegerArgumentType.getInteger(c, "deadline"),
                                                                                                StringArgumentType.getString(c, "objective"),
                                                                                                BoolArgumentType.getBool(c, "patrol")))))))))))));
        template.then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("field", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                                .templateSet(p, StringArgumentType.getString(c, "id"),
                                                        StringArgumentType.getString(c, "field"),
                                                        StringArgumentType.getString(c, "value"))))))));
        template.then(Commands.literal("duplicate")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                .templateDuplicate(p, StringArgumentType.getString(c, "id"))))));
        template.then(Commands.literal("enable")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                .templateSetEnabled(p, StringArgumentType.getString(c, "id"), true)))));
        template.then(Commands.literal("disable")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> player(c, p -> StrajaRuntime.get().missions()
                                .templateSetEnabled(p, StringArgumentType.getString(c, "id"), false)))));
        node.then(template);

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
        addV2MissionCommands(node);
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

    /** §25: OP 3 at the command boundary; Comisar authority remains in service. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> emergencyNode() {
        var node = adminOnly(Commands.literal("emergency"));
        node.then(Commands.literal("alert")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().emergencyRoleplay()
                                .alert(p, StringArgumentType.getString(c, "message"))))));
        node.then(Commands.literal("clear")
                .executes(c -> adminActor(c, StrajaRuntime.get().emergencyRoleplay()::clearUrgency)));
        node.then(Commands.literal("start")
                .executes(c -> adminActor(c, p -> StrajaRuntime.get().emergencyRoleplay()
                        .start(p, null, null, null)))
                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(1.0))
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().emergencyRoleplay()
                                .start(p, DoubleArgumentType.getDouble(c, "multiplier"), null, null)))
                        .then(Commands.argument("rounds", IntegerArgumentType.integer(1))
                                .executes(c -> adminActor(c, p -> StrajaRuntime.get().emergencyRoleplay()
                                        .start(p, DoubleArgumentType.getDouble(c, "multiplier"),
                                                IntegerArgumentType.getInteger(c, "rounds"), null)))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().emergencyRoleplay()
                                                .start(p, DoubleArgumentType.getDouble(c, "multiplier"),
                                                        IntegerArgumentType.getInteger(c, "rounds"),
                                                        StringArgumentType.getString(c, "reason"))))))));
        node.then(Commands.literal("end")
                .executes(c -> adminActor(c, StrajaRuntime.get().emergencyRoleplay()::end)));
        node.then(Commands.literal("status")
                .executes(c -> adminActor(c, StrajaRuntime.get().emergencyRoleplay()::status)));
        return node;
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

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> identityCardNode() {
        var node = adminOnly(Commands.literal("identity"));
        node.then(Commands.literal("list")
                .executes(c -> adminActor(c, StrajaRuntime.get().identityCards()::list)));
        node.then(Commands.literal("issue")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().identityCards()
                                .issue(p, target(c, "player"))))));
        node.then(Commands.literal("forge")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> adminActor(c, p -> StrajaRuntime.get().identityCards()
                                .forge(p, target(c, "player"))))));
        node.then(Commands.literal("revoke")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(c -> adminActor(c, p -> StrajaRuntime.get().identityCards()
                                        .revoke(p, StringArgumentType.getString(c, "id"),
                                                StringArgumentType.getString(c, "reason")))))));
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

    /** Shared typed-command policy: admin roots require OP 3; setup roots require OP 4. */
    static boolean isAdminOnly(String command) {
        return CommandPolicy.isAdminOnly(command);
    }

    static int requiredPermission(String command) {
        return CommandPolicy.permissionLevel(command);
    }

    static LiteralArgumentBuilder<CommandSourceStack> adminOnly(
            LiteralArgumentBuilder<CommandSourceStack> node) {
        int permission = requiredPermission(node.getLiteral());
        if (permission < CommandPermissions.ADMIN) {
            throw new IllegalArgumentException("Unclassified admin command: " + node.getLiteral());
        }
        return node.requires(source -> source.hasPermission(permission));
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

    private static LiteralArgumentBuilder<CommandSourceStack> v2PersonnelCommands() {
        var node = adminOnly(Commands.literal("personnel"));
        node.then(Commands.literal("list").executes(c -> {
            var source = c.getSource();
            for (var person : StrajaRuntime.get().v2Personnel().all())
                source.sendSystemMessage(Component.literal(person.serviceNumber + " " + person.playerUuid
                        + " " + person.membershipStatus + " " + person.careerGrade));
            return 1;
        }));
        node.then(Commands.literal("show").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> {
                    var target = target(c, "player"); var person = target == null ? null
                            : StrajaRuntime.get().v2Personnel().find(target.uuid().toString());
                    if (person == null) { c.getSource().sendFailure(Component.literal("Personnel V2 nu există.")); return 0; }
                    c.getSource().sendSystemMessage(Component.literal(person.playerUuid + " " + person.membershipStatus
                            + " " + person.careerGrade + " v" + person.version)); return 1;
                })));
        node.then(Commands.literal("authorize").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("grade", StringArgumentType.word()).executes(c -> {
                    var target = target(c, "player");
                    var grade = parseGrade(c, "grade");
                    StrajaRuntime.get().v2Personnel().authorize(actor(c).uuid().toString(), target.uuid().toString(), grade,
                            grade.fullTimeRequired() ? com.dwurdy.straja.domain.model.EmploymentMode.FULL_TIME
                                    : com.dwurdy.straja.domain.model.EmploymentMode.PART_TIME,
                            "COMMAND", "hq", "command:authorize:" + target.uuid());
                    return 1;
                }))));
        node.then(Commands.literal("profession").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    StrajaRuntime.get().v2Personnel().assignProfession(actor(c).uuid().toString(),
                            target(c, "player").uuid().toString(), StringArgumentType.getString(c, "name"));
                    return 1;
                }))));
        node.then(Commands.literal("appoint").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("station", StringArgumentType.word())
                                .then(Commands.argument("jurisdiction", StringArgumentType.word())
                                        .executes(c -> {
                                            StrajaRuntime.get().v2Personnel().appoint(actor(c).uuid().toString(),
                                                    target(c, "player").uuid().toString(),
                                                    parseAppointmentType(c, "type"),
                                                    StringArgumentType.getString(c, "station"),
                                                    StringArgumentType.getString(c, "jurisdiction"), null);
                                            return 1;
                                        }))))));
        node.then(Commands.literal("suspend").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { StrajaRuntime.get().v2Personnel().suspend(actor(c).uuid().toString(), target(c, "player").uuid().toString(), "COMMAND"); return 1; })));
        node.then(Commands.literal("reinstate").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { StrajaRuntime.get().v2Personnel().reinstate(actor(c).uuid().toString(), target(c, "player").uuid().toString()); return 1; })));
        node.then(Commands.literal("terminate").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { StrajaRuntime.get().v2Personnel().terminate(actor(c).uuid().toString(), target(c, "player").uuid().toString(), "COMMAND"); return 1; })));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2PromotionCommands() {
        var node = adminOnly(Commands.literal("promotion"));
        node.then(Commands.literal("list").executes(c -> {
            for (var application : StrajaRuntime.get().v2Promotions().all())
                c.getSource().sendSystemMessage(Component.literal(application.applicationId + " "
                        + application.subjectUuid + " " + application.status + " -> " + application.targetGrade));
            return 1;
        }));
        node.then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> showPromotion(c, StringArgumentType.getString(c, "id")))));
        node.then(Commands.literal("submit").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("grade", StringArgumentType.word()).executes(c -> {
                    var result = StrajaRuntime.get().v2Promotions().submit(
                            target(c, "player").uuid().toString(), parseGrade(c, "grade"));
                    c.getSource().sendSuccess(() -> Component.literal("Promotion " + result.applicationId + " creată."), false);
                    return 1;
                }))));
        node.then(Commands.literal("evidence").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("kind", StringArgumentType.word())
                        .then(Commands.argument("result", StringArgumentType.word()).executes(c -> {
                            var evidence = StrajaRuntime.get().v2Promotions().addEvidence(
                                    StringArgumentType.getString(c, "id"), actor(c).uuid().toString(),
                                    StringArgumentType.getString(c, "kind"), "COMMAND",
                                    StringArgumentType.getString(c, "result"), null,
                                    "command:" + actor(c).uuid() + ":" + StringArgumentType.getString(c, "id")
                                            + ":" + StringArgumentType.getString(c, "kind"));
                            c.getSource().sendSuccess(() -> Component.literal("Dovadă " + evidence.evidenceId + " atașată."), false);
                            return 1;
                        })))));
        node.then(Commands.literal("mark-ready").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Promotions().markReady(StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("approve").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("version", IntegerArgumentType.integer(0)).executes(c -> {
                    StrajaRuntime.get().v2Promotions().approve(actor(c).uuid().toString(),
                            StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "version")); return 1;
                }))));
        node.then(Commands.literal("reject").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> {
                    StrajaRuntime.get().v2Promotions().reject(actor(c).uuid().toString(),
                            StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "reason")); return 1;
                }))));
        node.then(Commands.literal("withdraw").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Promotions().withdraw(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id")); return 1; })));
        return node;
    }

    private static int showPromotion(CommandContext<CommandSourceStack> c, String id) {
        var application = StrajaRuntime.get().v2Promotions().find(id);
        if (application == null) { c.getSource().sendFailure(Component.literal("Promotion necunoscută.")); return 0; }
        c.getSource().sendSystemMessage(Component.literal(application.applicationId + " " + application.subjectUuid
                + " " + application.status + " v" + application.version + " -> " + application.targetGrade));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2DocumentCommands() {
        var node = adminOnly(Commands.literal("document"));
        node.then(Commands.literal("list").executes(c -> {
            var service = StrajaRuntime.get().v2Documents();
            for (var document : service.documents())
                c.getSource().sendSystemMessage(Component.literal(document.documentId + " " + document.type + " " + document.status));
            for (var instrument : service.instruments())
                c.getSource().sendSystemMessage(Component.literal(instrument.instrumentId + " " + instrument.instrumentType
                        + " " + instrument.remainingQuantity + "/" + instrument.initialQuantity));
            return 1;
        }));
        node.then(Commands.literal("inspect").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> {
                    var document = StrajaRuntime.get().v2Documents().inspect(StringArgumentType.getString(c, "id"));
                    if (document == null) { c.getSource().sendFailure(Component.literal("Document necunoscut.")); return 0; }
                    c.getSource().sendSystemMessage(Component.literal(document.documentId + " " + document.type + " "
                            + document.status + " subject=" + document.subject + " station=" + document.stationId)); return 1;
                })));
        node.then(Commands.literal("void").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Documents().revoke(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("expire").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Documents().expire(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("reprint").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Documents().reprint(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id"), "command:reprint:" + StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("instrument").then(Commands.literal("issue")
                .then(Commands.argument("holder", EntityArgument.player())
                        .then(Commands.argument("type", StringArgumentType.word())
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1)).executes(c -> {
                                    var instrument = StrajaRuntime.get().v2Documents().issueInstrument(
                                            actor(c).uuid().toString(), target(c, "holder").uuid().toString(),
                                            parseDocumentType(c, "type"), IntegerArgumentType.getInteger(c, "quantity"),
                                            "equipment", "hq", null, "command:" + actor(c).uuid() + ":" + target(c, "holder").uuid()
                                                    + ":" + StringArgumentType.getString(c, "type"));
                                    c.getSource().sendSuccess(() -> Component.literal("Instrument " + instrument.instrumentId + " emis."), false);
                                    return 1;
                                }))))));
        node.then(Commands.literal("redemption").then(Commands.argument("instrument", StringArgumentType.word())
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1)).executes(c -> {
                    var result = StrajaRuntime.get().v2Documents().redeem(actor(c).uuid().toString(),
                            StringArgumentType.getString(c, "instrument"), IntegerArgumentType.getInteger(c, "quantity"),
                            "hq", "command:" + actor(c).uuid() + ":" + StringArgumentType.getString(c, "instrument"));
                    if (!result.accepted()) { c.getSource().sendFailure(Component.literal(result.reason())); return 0; }
                    return 1;
                }))));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2EquipmentCommands() {
        var node = adminOnly(Commands.literal("equipment"));
        node.then(Commands.literal("ledger").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> {
                    for (var obligation : StrajaRuntime.get().v2EquipmentLedger()
                            .obligationsFor(target(c, "player").uuid().toString()))
                        c.getSource().sendSystemMessage(Component.literal(obligation.obligationId + " " + obligation.itemId
                                + " " + obligation.outstandingQuantity + "/" + obligation.issuedQuantity + " " + obligation.status));
                    return 1;
                })));
        node.then(Commands.literal("issue").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("instrument", StringArgumentType.word())
                        .then(Commands.argument("item", StringArgumentType.word())
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("operation", StringArgumentType.word())
                                                .executes(c -> {
                                                    String instrument = StringArgumentType.getString(c, "instrument");
                                                    var issue = StrajaRuntime.get().v2EquipmentLedger().createIssue(
                                                            actor(c).uuid().toString(), target(c, "player").uuid().toString(),
                                                            "hq", "none".equalsIgnoreCase(instrument) ? "" : instrument, "",
                                                            java.util.List.of(new com.dwurdy.straja.application.service.EquipmentLedgerService.RequestedLine(
                                                                    StringArgumentType.getString(c, "item"), IntegerArgumentType.getInteger(c, "quantity"))),
                                                            StringArgumentType.getString(c, "operation"));
                                                    c.getSource().sendSuccess(() -> Component.literal("Echipament " + issue.issueId + " rezervat."), false);
                                                    return 1;
                                                })))))));
        node.then(Commands.literal("fulfill").then(Commands.argument("issue", StringArgumentType.word())
                .then(Commands.argument("line", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                .then(Commands.argument("asset", StringArgumentType.word())
                                        .then(Commands.argument("operation", StringArgumentType.word())
                                                .executes(c -> {
                                                    StrajaRuntime.get().v2EquipmentLedger().fulfillLine(
                                                            StringArgumentType.getString(c, "issue"), StringArgumentType.getString(c, "line"),
                                                            IntegerArgumentType.getInteger(c, "quantity"), StringArgumentType.getString(c, "asset"),
                                                            StringArgumentType.getString(c, "operation"));
                                                    return 1;
                                                })))))));
        node.then(Commands.literal("return").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                .then(Commands.argument("operation", StringArgumentType.word())
                                        .executes(c -> {
                                            var result = StrajaRuntime.get().v2EquipmentLedger().returnQuantity(
                                                    actor(c).uuid().toString(), target(c, "player").uuid().toString(),
                                                    StringArgumentType.getString(c, "item"), IntegerArgumentType.getInteger(c, "quantity"),
                                                    StringArgumentType.getString(c, "operation"));
                                            return result.accepted() ? 1 : 0;
                                        }))))));
        node.then(Commands.literal("lost").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("debt", IntegerArgumentType.integer(0))
                        .executes(c -> { StrajaRuntime.get().v2EquipmentLedger().markLost(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "debt")); return 1; }))));
        node.then(Commands.literal("destroyed").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("debt", IntegerArgumentType.integer(0))
                        .executes(c -> { StrajaRuntime.get().v2EquipmentLedger().markDestroyed(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "debt")); return 1; }))));
        node.then(Commands.literal("waive").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("waiver", StringArgumentType.word())
                        .executes(c -> { StrajaRuntime.get().v2EquipmentLedger().waive(actor(c).uuid().toString(),
                                 StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "waiver")); return 1; }))));
        node.then(Commands.literal("debt").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> {
                    var obligation = StrajaRuntime.get().v2EquipmentLedger().obligationsFor(actor(c).uuid().toString()).stream()
                            .filter(value -> value.obligationId.equals(StringArgumentType.getString(c, "id"))).findFirst().orElse(null);
                    if (obligation == null) { c.getSource().sendFailure(Component.literal("Obligație necunoscută.")); return 0; }
                    c.getSource().sendSystemMessage(Component.literal("Datorie " + obligation.debtAmount + " status=" + obligation.status)); return 1;
                })));
        node.then(Commands.literal("reconcile").executes(c -> {
            int changed = StrajaRuntime.get().v2EquipmentLedger().reconcile();
            c.getSource().sendSystemMessage(Component.literal("Obligații reconciliate: " + changed)); return 1;
        }));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2MobilizationCommands() {
        var node = adminOnly(Commands.literal("mobilization"));
        node.then(Commands.literal("list").executes(c -> {
            for (var order : StrajaRuntime.get().v2Mobilizations().all())
                c.getSource().sendSystemMessage(Component.literal(order.mobilizationId + " " + order.specialistUuid
                        + " " + order.status + " until " + order.expiresAt));
            return 1;
        }));
        node.then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
            var order = StrajaRuntime.get().v2Mobilizations().find(StringArgumentType.getString(c, "id"));
            if (order == null) { c.getSource().sendFailure(Component.literal("Mobilizare necunoscută.")); return 0; }
            c.getSource().sendSystemMessage(Component.literal(order.mobilizationId + " " + order.status + " " + order.stationId)); return 1;
        })));
        node.then(Commands.literal("authorize").then(Commands.argument("specialist", EntityArgument.player())
                .then(Commands.argument("station", StringArgumentType.word())
                        .then(Commands.argument("jurisdiction", StringArgumentType.word())
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                        .executes(c -> {
                                            var order = StrajaRuntime.get().v2Mobilizations().authorize(
                                                    actor(c).uuid().toString(), target(c, "specialist").uuid().toString(),
                                                    StringArgumentType.getString(c, "station"),
                                                    StringArgumentType.getString(c, "jurisdiction"),
                                                    IntegerArgumentType.getInteger(c, "hours") * 3_600_000L,
                                                    "", "", "COMMAND");
                                            c.getSource().sendSuccess(() -> Component.literal(
                                                    "Mobilizare " + order.mobilizationId + " autorizată."), false);
                                            return 1;
                                        }))))));
        node.then(Commands.literal("muster").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Mobilizations().muster(actor(c).uuid().toString(), StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("activate").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Mobilizations().activate(actor(c).uuid().toString(), StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("end").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Mobilizations().demobilize(actor(c).uuid().toString(), StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> { StrajaRuntime.get().v2Mobilizations().cancel(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "reason")); return 1; }))));
        return node;
    }

    private static void addV2MissionCommands(LiteralArgumentBuilder<CommandSourceStack> node) {
        node.then(Commands.literal("publish")
                .then(Commands.argument("beneficiary", EntityArgument.player())
                        .then(Commands.argument("type", StringArgumentType.word())
                                .then(Commands.argument("deadline", LongArgumentType.longArg())
                                        .then(Commands.argument("max", IntegerArgumentType.integer(1))
                                                .executes(c -> {
                                                    var mission = StrajaRuntime.get().v2Missions().publish(
                                                            actor(c).uuid().toString(), target(c, "beneficiary").uuid().toString(),
                                                            "hq", "", StringArgumentType.getString(c, "type"),
                                                            LongArgumentType.getLong(c, "deadline"),
                                                            IntegerArgumentType.getInteger(c, "max"));
                                                    c.getSource().sendSuccess(() -> Component.literal("Misiune V2 " + mission.id + " publicată."), false);
                                                    return 1;
                                                }))))));
        node.then(Commands.literal("claim").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Missions().claim(actor(c).uuid().toString(), StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("begin").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Missions().begin(actor(c).uuid().toString(), StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("submit").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("evidence", StringArgumentType.greedyString())
                        .executes(c -> { StrajaRuntime.get().v2Missions().submit(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "evidence")); return 1; }))));
        node.then(Commands.literal("verify").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Missions().verify(actor(c).uuid().toString(), StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("reject-report").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> { StrajaRuntime.get().v2Missions().rejectReport(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "reason")); return 1; }))));
        node.then(Commands.literal("reassign").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("beneficiary", EntityArgument.player())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(c -> { StrajaRuntime.get().v2Missions().reassign(actor(c).uuid().toString(),
                                        StringArgumentType.getString(c, "id"), target(c, "beneficiary").uuid().toString(),
                                        StringArgumentType.getString(c, "reason")); return 1; })))));
        node.then(Commands.literal("extend").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                        .executes(c -> { StrajaRuntime.get().v2Missions().extendDeadline(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "hours") * 3_600_000L); return 1; }))));
        node.then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> { StrajaRuntime.get().v2Missions().cancel(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "reason")); return 1; }))));
        node.then(Commands.literal("evidence").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("reference", StringArgumentType.greedyString())
                                .executes(c -> { StrajaRuntime.get().v2Missions().addEvidence(
                                        actor(c).uuid().toString(), StringArgumentType.getString(c, "id"),
                                        StringArgumentType.getString(c, "type"), StringArgumentType.getString(c, "reference")); return 1; })))));
        node.then(Commands.literal("generator").executes(c -> {
            c.getSource().sendSystemMessage(Component.literal(String.join(", ", StrajaRuntime.get().v2Generators().professions()))); return 1;
        }).then(Commands.literal("publish").then(Commands.argument("profession", StringArgumentType.word())
                .then(Commands.argument("beneficiary", EntityArgument.player())
                        .then(Commands.argument("offer", StringArgumentType.word())
                                .executes(c -> {
                                    var mission = StrajaRuntime.get().v2Generators().publishFor(
                                            StringArgumentType.getString(c, "profession"), target(c, "beneficiary").uuid().toString(),
                                            "hq", "", StrajaRuntime.get().nowMillis(), StringArgumentType.getString(c, "offer"),
                                            StrajaRuntime.get().v2Missions());
                                    c.getSource().sendSuccess(() -> Component.literal("Ofertă " + mission.id + " publicată."), false); return 1;
                                }))))));
        node.then(Commands.literal("pool").executes(c -> {
            c.getSource().sendSystemMessage(Component.literal("Generator pool: "
                    + String.join(", ", StrajaRuntime.get().v2Generators().professions()))); return 1;
        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2CampaignCommands() {
        var node = adminOnly(Commands.literal("campaign"));
        node.then(Commands.literal("create").then(Commands.argument("type", StringArgumentType.word())
                .then(Commands.argument("station", StringArgumentType.word())
                        .then(Commands.argument("jurisdiction", StringArgumentType.word())
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("quota", LongArgumentType.longArg(1))
                                                .executes(c -> {
                                                    long now = StrajaRuntime.get().nowMillis();
                                                    var campaign = StrajaRuntime.get().v2Campaigns().create(
                                                            actor(c).uuid().toString(), StringArgumentType.getString(c, "type"),
                                                            StringArgumentType.getString(c, "station"),
                                                            StringArgumentType.getString(c, "jurisdiction"), now,
                                                            now + IntegerArgumentType.getInteger(c, "hours") * 3_600_000L,
                                                            LongArgumentType.getLong(c, "quota"));
                                                    c.getSource().sendSuccess(() -> Component.literal(
                                                            "Campanie " + campaign.campaignId + " creată."), false);
                                                    return 1;
                                                })))))));
        node.then(Commands.literal("list").executes(c -> {
            for (var campaign : StrajaRuntime.get().v2Campaigns().all())
                c.getSource().sendSystemMessage(Component.literal(campaign.campaignId + " " + campaign.status + " "
                        + campaign.quantityAccepted + "/" + campaign.globalQuota));
            return 1;
        }));
        node.then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
            var campaign = StrajaRuntime.get().v2Campaigns().find(StringArgumentType.getString(c, "id"));
            if (campaign == null) { c.getSource().sendFailure(Component.literal("Campanie necunoscută.")); return 0; }
            c.getSource().sendSystemMessage(Component.literal(campaign.campaignId + " " + campaign.status + " quota=" + campaign.globalQuota)); return 1;
        })));
        node.then(Commands.literal("start").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Campaigns().start(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("end").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Campaigns().end(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("close").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> { StrajaRuntime.get().v2Campaigns().end(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> { StrajaRuntime.get().v2Campaigns().cancel(actor(c).uuid().toString(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "reason")); return 1; }))));
        node.then(Commands.literal("extend").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                        .executes(c -> { StrajaRuntime.get().v2Campaigns().extend(actor(c).uuid().toString(),
                        StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "hours") * 3_600_000L); return 1; }))));
        node.then(Commands.literal("quota").then(Commands.argument("campaign", StringArgumentType.word())
                .then(Commands.argument("mission", StringArgumentType.word())
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("quantity", LongArgumentType.longArg(1))
                                        .then(Commands.argument("operation", StringArgumentType.word())
                                                .executes(c -> {
                                                    var reservation = StrajaRuntime.get().v2Campaigns().reserve(
                                                            StringArgumentType.getString(c, "campaign"),
                                                            StringArgumentType.getString(c, "mission"),
                                                            target(c, "player").uuid().toString(),
                                                            LongArgumentType.getLong(c, "quantity"),
                                                            StringArgumentType.getString(c, "operation"));
                                                    try {
                                                        StrajaRuntime.get().v2Missions().attachQuotaReservation(
                                                                actor(c).uuid().toString(),
                                                                StringArgumentType.getString(c, "mission"),
                                                                reservation.reservationId);
                                                    } catch (RuntimeException error) {
                                                        StrajaRuntime.get().v2Campaigns().release(reservation.reservationId);
                                                        throw error;
                                                    }
                                                    c.getSource().sendSuccess(() -> Component.literal(
                                                            "Rezervare " + reservation.reservationId + " creată."), false);
                                                    return 1;
                                                })))))));
        node.then(Commands.literal("fulfill").then(Commands.argument("reservation", StringArgumentType.word())
                .then(Commands.argument("quantity", LongArgumentType.longArg(1))
                        .then(Commands.argument("operation", StringArgumentType.word())
                                .executes(c -> {
                                    var reservation = StrajaRuntime.get().v2Campaigns().fulfill(
                                            StringArgumentType.getString(c, "reservation"),
                                            LongArgumentType.getLong(c, "quantity"),
                                            StringArgumentType.getString(c, "operation"));
                                    c.getSource().sendSuccess(() -> Component.literal(
                                            "Livrare quota " + reservation.reservationId + " "
                                                    + reservation.fulfilledQuantity + "/" + reservation.reservedQuantity), false);
                                    return 1;
                                })))));
        node.then(Commands.literal("release").then(Commands.argument("reservation", StringArgumentType.word())
                .executes(c -> {
                    var reservation = StrajaRuntime.get().v2Campaigns().release(
                            StringArgumentType.getString(c, "reservation"));
                    c.getSource().sendSuccess(() -> Component.literal(
                            "Rezervare " + reservation.reservationId + " eliberată."), false);
                    return 1;
                })));
        node.then(Commands.literal("reservations").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> {
                    for (var reservation : StrajaRuntime.get().v2Campaigns().reservations(StringArgumentType.getString(c, "id")))
                        c.getSource().sendSystemMessage(Component.literal(reservation.reservationId + " "
                                + reservation.status + " " + reservation.fulfilledQuantity + "/" + reservation.reservedQuantity));
                    return 1;
                })));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2SettlementCommands() {
        var node = adminOnly(Commands.literal("settlement"));
        node.then(Commands.literal("list").executes(c -> {
            for (var settlement : StrajaRuntime.get().v2Settlements().all())
                c.getSource().sendSystemMessage(Component.literal(settlement.settlementId + " " + settlement.playerUuid
                        + " " + settlement.status + " amount=" + settlement.amount));
            return 1;
        }));
        node.then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
            var settlement = StrajaRuntime.get().v2Settlements().find(StringArgumentType.getString(c, "id"));
            if (settlement == null) { c.getSource().sendFailure(Component.literal("Decontare necunoscută.")); return 0; }
            c.getSource().sendSystemMessage(Component.literal(settlement.settlementId + " " + settlement.status
                    + " " + settlement.amount + " " + settlement.settlementKey)); return 1;
        })));
        node.then(Commands.literal("review").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> {
                    StrajaRuntime.get().v2Settlements().markReview(StringArgumentType.getString(c, "id"),
                            StringArgumentType.getString(c, "reason")); return 1;
                }))));
        node.then(Commands.literal("retry").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> {
                    String id = StringArgumentType.getString(c, "id");
                    var settlement = StrajaRuntime.get().v2Settlements().retry(id);
                    var player = StrajaRuntime.get().playerQueries().findPlayer(settlement.playerUuid);
                    if (player != null) StrajaRuntime.get().v2Settlements().payout(settlement, player);
                    return 1;
                })));
        node.then(Commands.literal("void").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> {
                    StrajaRuntime.get().v2Settlements().voidSettlement(StringArgumentType.getString(c, "id"),
                            StringArgumentType.getString(c, "reason")); return 1;
                }))));
        node.then(Commands.literal("reconcile").executes(c -> {
            int changed = StrajaRuntime.get().v2Settlements().reconcile();
            c.getSource().sendSystemMessage(Component.literal("Decontări reconciliate: " + changed));
            return 1;
        }));
        return node;
    }

    private static com.dwurdy.straja.domain.model.CareerGrade parseGrade(CommandContext<CommandSourceStack> c, String name) {
        try { return com.dwurdy.straja.domain.model.CareerGrade.valueOf(StringArgumentType.getString(c, name).toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("career grade invalid"); }
    }

    private static com.dwurdy.straja.domain.model.DocumentType parseDocumentType(CommandContext<CommandSourceStack> c, String name) {
        try { return com.dwurdy.straja.domain.model.DocumentType.valueOf(StringArgumentType.getString(c, name).toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("document type invalid"); }
    }

    private static com.dwurdy.straja.domain.model.AppointmentType parseAppointmentType(
            CommandContext<CommandSourceStack> c, String name) {
        try {
            return com.dwurdy.straja.domain.model.AppointmentType.valueOf(
                    StringArgumentType.getString(c, name).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("appointment type invalid");
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2StationCommands() {
        var node = adminOnly(Commands.literal("station"));
        node.then(Commands.literal("status").executes(c -> {
            var resolution = StrajaRuntime.get().v2Stations().resolve("hq", "", "");
            c.getSource().sendSystemMessage(Component.literal(resolution.available()
                    ? "Stație activă: " + resolution.station().stationId
                    : "Stațiile nu au putut fi rezolvate: " + resolution.reason())); return resolution.available() ? 1 : 0;
        }));
        node.then(Commands.literal("inventory").executes(c -> {
            var station = StrajaRuntime.get().v2Stations().get("hq");
            if (station == null) return 0;
            c.getSource().sendSystemMessage(Component.literal("Inventar stație " + station.stationId + ": "
                    + (station.inventoryAccounts.isEmpty() ? "gol" : station.inventoryAccounts.toString())));
            return 1;
        }));
        node.then(Commands.literal("budget").executes(c -> {
            var station = StrajaRuntime.get().v2Stations().get("hq");
            if (station == null) return 0;
            c.getSource().sendSystemMessage(Component.literal("Buget stație " + station.stationId + ": "
                    + (station.budgetAccounts.isEmpty() ? "gol" : station.budgetAccounts.toString())));
            return 1;
        }));
        node.then(Commands.literal("validate").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .executes(c -> {
                    var errors = StrajaRuntime.get().v2Stations().validateFallbacks();
                    c.getSource().sendSystemMessage(Component.literal(errors.isEmpty() ? "Stațiile sunt valide." : String.join(", ", errors)));
                    return errors.isEmpty() ? 1 : 0;
                }));
        node.then(Commands.literal("create").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> { StrajaRuntime.get().v2Stations().create(
                                        StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "name")); return 1; }))));
        node.then(Commands.literal("remove").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> { StrajaRuntime.get().v2Stations().remove(StringArgumentType.getString(c, "id")); return 1; })));
        node.then(Commands.literal("set-fallback").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("fallback", StringArgumentType.word())
                                .executes(c -> { StrajaRuntime.get().v2Stations().setFallback(
                                        StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "fallback")); return 1; }))));
        node.then(Commands.literal("set-jurisdiction").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("jurisdiction", StringArgumentType.greedyString())
                                .executes(c -> { StrajaRuntime.get().v2Stations().setJurisdictions(
                                 StringArgumentType.getString(c, "id"), java.util.List.of(StringArgumentType.getString(c, "jurisdiction"))); return 1; }))));
        node.then(Commands.literal("set-location").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("role", StringArgumentType.word())
                                .executes(c -> {
                                    PlayerGateway player = actor(c);
                                    var location = new com.dwurdy.straja.domain.model.SetupData.Location();
                                    location.dimension = player.dimension(); location.x = player.x();
                                    location.y = player.y(); location.z = player.z();
                                    StrajaRuntime.get().v2Stations().setLocation(
                                            StringArgumentType.getString(c, "id"),
                                            StringArgumentType.getString(c, "role"), location);
                                    return 1;
                                }))));
        node.then(Commands.literal("set-message-template").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("template", StringArgumentType.greedyString())
                                        .executes(c -> { StrajaRuntime.get().v2Stations().setMessageTemplate(
                                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "key"),
                                                StringArgumentType.getString(c, "template")); return 1; })))));
        node.then(Commands.literal("bind-npc").requires(source -> source.hasPermission(CommandPermissions.SETUP))
                .then(Commands.argument("npc", StringArgumentType.word())
                        .then(Commands.argument("station", StringArgumentType.word())
                                .executes(c -> { StrajaRuntime.get().npcs().bindStation(
                                        StringArgumentType.getString(c, "npc"), StringArgumentType.getString(c, "station")); return 1; }))));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2DoctorCommands() {
        var node = adminOnly(Commands.literal("doctor"));
        node.then(Commands.literal("consistency").executes(c -> {
            return doctorReport(c);
        }));
        for (String check : new String[]{"operations", "equipment", "settlements", "stations", "outbox"})
            node.then(Commands.literal(check).executes(c -> doctorReport(c, check)));
        return node;
    }

    private static int doctorReport(CommandContext<CommandSourceStack> c) {
        return doctorReport(c, "consistency");
    }

    private static int doctorReport(CommandContext<CommandSourceStack> c, String section) {
        var issues = StrajaRuntime.get().v2Consistency().check(section);
        c.getSource().sendSystemMessage(Component.literal(issues.isEmpty()
                ? "Doctor: OK" : "Doctor: " + String.join(", ", issues)));
        return issues.isEmpty() ? 1 : 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> v2OutboxCommands() {
        var node = adminOnly(Commands.literal("outbox"));
        node.then(Commands.literal("status").executes(c -> {
            c.getSource().sendSystemMessage(Component.literal("Outbox pending: " + StrajaRuntime.get().v2Outbox().pending().size()));
            return 1;
        }));
        node.then(Commands.literal("pending").executes(c -> {
            for (var event : StrajaRuntime.get().v2Outbox().pending())
                c.getSource().sendSystemMessage(Component.literal(event.eventId + " " + event.eventType + " " + event.status));
            return 1;
        }));
        node.then(Commands.literal("retry").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> StrajaRuntime.get().v2Outbox().retry(StringArgumentType.getString(c, "id")) ? 1 : 0)));
        node.then(Commands.literal("dead-letter").executes(c -> {
            for (var event : StrajaRuntime.get().v2Outbox().deadLetters())
                c.getSource().sendSystemMessage(Component.literal(event.eventId + " " + event.eventType + " attempts=" + event.attempts));
            return 1;
        }));
        node.then(Commands.literal("test").requires(source -> source.hasPermission(CommandPermissions.SETUP)).executes(c -> {
            String key = "outbox-test:" + StrajaRuntime.get().nowMillis();
            var event = StrajaRuntime.get().v2Outbox().enqueue("MAJOR_INCIDENT", key,
                    new com.dwurdy.straja.application.service.OutboxService.SafePayload(
                            "{\"eventType\":\"MAJOR_INCIDENT\",\"aggregateId\":\"OUTBOX_TEST\",\"title\":\"outbox test\",\"severity\":\"TEST\"}"));
            c.getSource().sendSystemMessage(Component.literal("Outbox test enqueued: " + event.eventId));
            return 1;
        }));
        return node;
    }

    private static int adminActor(CommandContext<CommandSourceStack> ctx,
                                  java.util.function.Consumer<PlayerGateway> op) {
        op.accept(actor(ctx));
        return 1;
    }

    /** Pure policy facade kept separate so command-surface tests need no MC runtime. */
    static final class CommandPolicy {
        private CommandPolicy() {}

        static boolean isAdminOnly(String command) {
            return CommandPermissions.isAdminOnly(command);
        }

        static int permissionLevel(String command) {
            return CommandPermissions.permissionLevel(command);
        }

        static List<String> helpLines(boolean admin) {
            if (!admin) {
                return List.of(
                        "/straja status — starea și rangul tău",
                        "/straja rules | regulament — regulamentul",
                        "/straja stop — încheierea serviciului");
            }
            return List.of(
                    "§lStraja — help administrativ",
                    "Detalii: /straja <comandă> help",
                    "OP 3: backup, recrutare, personal, operațiuni și arhivă",
                    "OP 4: setup, policy, migrare, NPC, debug și test",
                    "/straja status | rules | regulament | stop",
                    "/straja help — acest index; /straja <comandă> help — detalii",
                    "/straja invite|recruit|recrute|quiz | promote|demote|suspend|fire|reinstate",
                    "/straja start|special|resign|demisie|rejoin | salary|coins|food|kit|merit",
                    "/straja report|message|request <text> | inbox",
                    "/straja mission|cuffs|prison|fine|complaint|room|archive|identity",
                    "/straja emergency ...",
                    "/straja checkpoint add|remove ... | set-checkpoint | set-mission-time | set-location [OP 4]",
                    "/straja setup ... | policy ... | migrate ... | npc ... | debug ... | test ... [OP 4]");
        }
    }
}
