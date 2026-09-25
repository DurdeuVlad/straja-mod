package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.bootstrap.NpcPresentationRuntime;
import com.dwurdy.straja.domain.model.NpcRegistry;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.SetupData;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

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

        npc.then(Commands.literal("provider")
                .then(Commands.literal("status").executes(ctx -> providerStatus(ctx)))
                .then(Commands.literal("recover").executes(ctx -> providerRecover(ctx)))
                .then(Commands.literal("migrate")
                        .then(Commands.argument("provider", StringArgumentType.word())
                                .executes(ctx -> providerMigrate(ctx,
                                        StringArgumentType.getString(ctx, "provider"))))));

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

        npc.then(Commands.literal("bind-custom")
                .then(Commands.argument("host", StringArgumentType.word())
                        .then(Commands.argument("role", StringArgumentType.word())
                                .executes(ctx -> bindCustom(ctx, "hq"))
                                .then(Commands.argument("station", StringArgumentType.word())
                                        .executes(ctx -> bindCustom(ctx,
                                                StringArgumentType.getString(ctx, "station")))))));

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

    private static int providerStatus(CommandContext<CommandSourceStack> ctx) {
        try {
            var state = NpcPresentationRuntime.require();
            var providers = state.providers();
            ctx.getSource().sendSystemMessage(Component.literal(
                    "NPC provider mode=" + state.activeProvider().value()
                            + " debug-text-enabled=" + state.debugTextEnabled()
                            + " customnpcs-available=" + providers.available(NpcProviderId.CUSTOM_NPCS)
                            + " debug-text-available=" + providers.available(NpcProviderId.DEBUG_TEXT)));
            for (var entry : state.lifecycle().bindings().values()) {
                var inspection = state.lifecycle().inspect(entry.bindingId());
                ctx.getSource().sendSystemMessage(Component.literal(
                        "  " + entry.bindingId() + " | provider=" + entry.providerId().value()
                                + " | profile=" + entry.surfaceProfileId().value()
                                + " | host=" + entry.hostEntityUuid()
                                + " | state=" + inspection.state()
                                + (inspection.pendingOperation() == null
                                        ? "" : " | pending=" + inspection.pendingOperation())));
            }
            return 1;
        } catch (IllegalStateException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }

    private static int providerRecover(CommandContext<CommandSourceStack> ctx) {
        try {
            var report = NpcPresentationRuntime.require().migration().recover();
            int failures = 0;
            for (var item : report.items()) {
                if (item.result().status() != com.dwurdy.straja.domain.model.NpcProviderResult.Status.ACCEPTED) {
                    failures++;
                }
                ctx.getSource().sendSystemMessage(Component.literal(
                        item.bindingId() + " -> " + item.result().status() + " " + item.result().message()));
            }
            return failures == 0 ? 1 : 0;
        } catch (IllegalStateException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }

    private static int providerMigrate(
            CommandContext<CommandSourceStack> ctx, String rawProvider) {
        NpcProviderId target;
        try {
            target = NpcProviderId.of(rawProvider);
        } catch (IllegalArgumentException error) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown provider. Valid values: customnpcs, debug-text."));
            return 0;
        }
        try {
            var state = NpcPresentationRuntime.require();
            if (target.equals(NpcProviderId.DEBUG_TEXT) && !state.debugTextEnabled()) {
                ctx.getSource().sendFailure(Component.literal(
                        "debug-text is disabled by the explicit NPC and debug security gates."));
                return 0;
            }
            var result = state.migration().migrate(target, ctx.getSource().getTextName());
            ctx.getSource().sendSystemMessage(Component.literal(
                    "NPC provider migration: " + result.result().status()
                            + " — " + result.result().message()));
            if (!result.rollback().isEmpty()) {
                ctx.getSource().sendFailure(Component.literal(
                        "Migration required rollback; inspect provider status before retrying."));
            }
            return result.result().status() == com.dwurdy.straja.domain.model.NpcProviderResult.Status.ACCEPTED
                    ? 1 : 0;
        } catch (IllegalStateException | IllegalArgumentException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
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

    private static int bindCustom(CommandContext<CommandSourceStack> ctx, String station) {
        String host = StringArgumentType.getString(ctx, "host");
        String role = StringArgumentType.getString(ctx, "role");
        CommandSourceStack source = ctx.getSource();
        String playerUuid = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                ? player.getUUID().toString() : "";
        String actorId = NpcProvisioningActorIdentity.fromSource(source.getTextName(), playerUuid);
        var result = NpcPresentationRuntime.bindCustomNpc(host, role, station, actorId);
        if (result.status() != com.dwurdy.straja.domain.model.NpcProviderResult.Status.ACCEPTED) {
            ctx.getSource().sendFailure(Component.literal(
                    "CustomNPCs binding failed: " + result.code() + " — " + result.message()));
            return 0;
        }
        ctx.getSource().sendSystemMessage(Component.literal(
                "CustomNPCs NPC bound to the Straja " + role + " admission surface."));
        return 1;
    }

    /**
     * Guided setup: spawns every missing physical role at its configured HQ
     * location. It refuses to partially spawn a set when one of those locations
     * is missing, because a row beside the executor would silently violate the
     * four-NPC building layout.
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
        var setup = runtime.context().setup().read();
        var missingLocations = new java.util.ArrayList<String>();
        var roleLocations = new java.util.LinkedHashMap<String, SetupData.Location>();
        var roleLevels = new java.util.LinkedHashMap<String, net.minecraft.server.level.ServerLevel>();
        for (String role : missing) {
            String locationKey = SetupData.npcLocationKey(role);
            var location = setup.location(locationKey);
            if (location == null) {
                missingLocations.add(role + " (" + locationKey + ")");
            } else {
                roleLocations.put(role, location);
                String dimensionName = location.dimension == null ? "" : location.dimension;
                ResourceLocation dimensionId = ResourceLocation.tryParse(dimensionName);
                if (dimensionId == null) {
                    missingLocations.add(role + " (dimensiune invalidă: " + dimensionName + ")");
                } else {
                    ResourceKey<Level> dimensionKey = ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dimensionId);
                    var level = ctx.getSource().getServer().getLevel(dimensionKey);
                    if (level == null) {
                        missingLocations.add(role + " (dimensiune neîncărcată: " + dimensionName + ")");
                    } else {
                        roleLevels.put(role, level);
                    }
                }
            }
        }
        if (!missingLocations.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Lipsesc locațiile NPC-urilor: " + String.join(", ", missingLocations)
                            + ". Folosește /straja set-location <nume> înainte de setup npcs."));
            return 0;
        }
        for (String role : missing) {
            var location = roleLocations.get(role);
            var level = roleLevels.get(role);
            var pos = new Vec3(location.x + 0.5, location.y, location.z + 0.5);
            var entity = spawnRoleEntity(runtime, level, role, pos);
            entity.setYRot(0f);
            ctx.getSource().sendSystemMessage(Component.literal(
                    "NPC " + role + " creat la " + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z));
        }
        ctx.getSource().sendSystemMessage(Component.literal(
                missing.size() + " NPC-uri spawnate în locațiile configurate ale sediului."));
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
