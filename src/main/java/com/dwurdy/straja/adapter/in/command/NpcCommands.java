package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.NpcRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * /straja npc * — console/RCON-safe NPC administration. The persistent NPC
 * registry is the source of truth: admin operations always update the record
 * and are reflected onto the live entity only when it is loaded. This keeps
 * every operation usable from the dedicated server console even when the
 * entity's chunk is not ticking.
 */
final class NpcCommands {
    private NpcCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        var npc = StrajaCommands.adminOnly(Commands.literal("npc"));

        npc.then(Commands.literal("list").executes(ctx -> {
            var runtime = StrajaRuntime.get();
            if (runtime == null) return 0;
            var records = runtime.npcs().list();
            if (records.isEmpty()) {
                ctx.getSource().sendSystemMessage(Component.literal("Niciun NPC Straja înregistrat."));
                return 1;
            }
            for (var record : records) {
                String displayName = record.displayName == null ? "" : record.displayName;
                String skin = record.skin == null ? "" : record.skin;
                ctx.getSource().sendSystemMessage(Component.literal(
                        record.entityUuid + " | rol: " + (record.role == null ? "" : record.role)
                                + (displayName.isEmpty() ? "" : " | nume: " + displayName)
                                + (skin.isEmpty() ? "" : " | skin: " + skin)));
            }
            return 1;
        }));

        npc.then(Commands.literal("spawn")
                .then(Commands.argument("role", StringArgumentType.word())
                        .executes(ctx -> spawn(ctx, ctx.getSource().getPosition()))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos"))))));

        npc.then(Commands.literal("assign")
                .then(Commands.argument("npc", StringArgumentType.word())
                        .then(Commands.argument("role", StringArgumentType.word())
                                .executes(ctx -> {
                                    String role = StringArgumentType.getString(ctx, "role");
                                    var runtime = StrajaRuntime.get();
                                    if (runtime == null) return 0;
                                    var record = record(ctx, runtime);
                                    if (record == null) return 0;
                                    var result = runtime.npcs().assignRole(record.entityUuid, role);
                                    if (!result.ok()) {
                                        ctx.getSource().sendFailure(Component.literal("Rol necunoscut: " + role));
                                        return 0;
                                    }
                                    var entity = loaded(ctx, record.entityUuid);
                                    if (entity != null) entity.setRoleId(role);
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "NPC " + record.entityUuid + " are rolul " + role + "."));
                                    return 1;
                                }))));

        npc.then(Commands.literal("set-name")
                .then(Commands.argument("npc", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    var runtime = StrajaRuntime.get();
                                    if (runtime == null) return 0;
                                    var record = record(ctx, runtime);
                                    if (record == null) return 0;
                                    String name = StringArgumentType.getString(ctx, "name");
                                    runtime.npcs().setName(record.entityUuid, name);
                                    var entity = loaded(ctx, record.entityUuid);
                                    if (entity != null) entity.setCustomName(Component.literal(name));
                                    ctx.getSource().sendSystemMessage(Component.literal("Numele a fost salvat."));
                                    return 1;
                                }))));

        npc.then(Commands.literal("set-skin")
                .then(Commands.argument("npc", StringArgumentType.word())
                        .then(Commands.argument("skin", StringArgumentType.word())
                                .executes(ctx -> {
                                    var runtime = StrajaRuntime.get();
                                    if (runtime == null) return 0;
                                    var record = record(ctx, runtime);
                                    if (record == null) return 0;
                                    String skin = StringArgumentType.getString(ctx, "skin");
                                    runtime.npcs().setSkin(record.entityUuid, skin);
                                    var entity = loaded(ctx, record.entityUuid);
                                    if (entity != null) entity.setSkin(skin);
                                    ctx.getSource().sendSystemMessage(Component.literal("Skin salvat: " + skin));
                                    return 1;
                                }))));

        npc.then(Commands.literal("remove")
                .then(Commands.argument("npc", StringArgumentType.word())
                        .executes(ctx -> {
                            var runtime = StrajaRuntime.get();
                            if (runtime == null) return 0;
                            var record = record(ctx, runtime);
                            if (record == null) return 0;
                            runtime.npcs().remove(record.entityUuid);
                            var entity = loaded(ctx, record.entityUuid);
                            if (entity != null) entity.discard();
                            ctx.getSource().sendSystemMessage(Component.literal("NPC eliminat."));
                            return 1;
                        })));

        return npc;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        String role = StringArgumentType.getString(ctx, "role");
        var runtime = StrajaRuntime.get();
        if (runtime == null) return 0;
        if (!com.dwurdy.straja.application.service.NpcAdminService.KNOWN_ROLES.contains(role)) {
            ctx.getSource().sendFailure(Component.literal(
                    "Rol necunoscut: " + role + ". Valide: "
                            + com.dwurdy.straja.application.service.NpcAdminService.KNOWN_ROLES));
            return 0;
        }
        var level = ctx.getSource().getLevel();
        var entity = spawnRoleEntity(runtime, level, role, pos);
        ctx.getSource().sendSystemMessage(Component.literal(
                "NPC " + role + " creat: " + entity.getStringUUID()
                        + " la " + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z));
        return 1;
    }

    /**
     * Guided setup: spawns every role missing from the registry in a row next
     * to the executor. Idempotent — roles already registered are skipped, so
     * re-running only fills gaps. Works from console/RCON (source position).
     */
    static int spawnMissing(CommandContext<CommandSourceStack> ctx) {
        var runtime = StrajaRuntime.get();
        if (runtime == null) return 0;
        var missing = com.dwurdy.straja.domain.model.SetupChecklist.missingNpcRoles(
                runtime.context().npcs().read(),
                com.dwurdy.straja.application.service.NpcAdminService.ROLE_ORDER);
        if (missing.isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.literal("Toate NPC-urile Straja sunt deja înregistrate."));
            return 1;
        }
        var level = ctx.getSource().getLevel();
        var base = ctx.getSource().getPosition();
        float facing = ctx.getSource().getRotation().y + 180f;
        int index = 0;
        for (String role : missing) {
            var pos = base.add(index * 2.0, 0, 0);
            var entity = spawnRoleEntity(runtime, level, role, pos);
            entity.setYRot(facing);
            ctx.getSource().sendSystemMessage(Component.literal(
                    "NPC " + role + " creat la " + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z));
            index++;
        }
        ctx.getSource().sendSystemMessage(Component.literal(
                missing.size() + " NPC-uri spawnate. Mută-le/numește-le cu /straja npc …"));
        return 1;
    }

    private static StrajaNpcEntity spawnRoleEntity(StrajaRuntime runtime,
            net.minecraft.server.level.ServerLevel level, String role, Vec3 pos) {
        var entity = StrajaNpcEntity.spawn(level, pos.x, pos.y, pos.z, role, null, null);
        runtime.npcs().register(entity.getStringUUID(), role);
        return entity;
    }

    /** Resolves the "npc" argument to a registry record (uuid or display name). */
    private static NpcRegistry.Record record(CommandContext<CommandSourceStack> ctx, StrajaRuntime runtime) {
        String key = StringArgumentType.getString(ctx, "npc");
        var registry = runtime.context().npcs().read();
        var record = registry.npcs.get(key);
        if (record == null) {
            for (var candidate : registry.npcs.values()) {
                if (key.equalsIgnoreCase(candidate.displayName)) {
                    record = candidate;
                    break;
                }
            }
        }
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal("NPC necunoscut: " + key));
        }
        return record;
    }

    /** Returns the live entity when its chunk is loaded, else null. */
    private static StrajaNpcEntity loaded(CommandContext<CommandSourceStack> ctx, String entityUuid) {
        try {
            var uuid = java.util.UUID.fromString(entityUuid);
            for (var level : ctx.getSource().getServer().getAllLevels()) {
                var entity = level.getEntity(uuid);
                if (entity instanceof StrajaNpcEntity npc) return npc;
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
