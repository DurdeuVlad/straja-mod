package com.dwurdy.straja.adapter.out.npc.customnpcs;

import com.dwurdy.straja.application.port.in.NpcProvisioningUseCase;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionUseCase;
import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcHostLocation;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Optional CustomNPCs player-facing adapter.
 *
 * <p>The CustomNPCs jar is intentionally not a compile-time dependency. The
 * public API is discovered at runtime and all calls cross this class's
 * reflection boundary. That keeps Straja loadable when CustomNPCs is absent
 * and leaves StoryNPC free to implement the same provider port later.</p>
 *
 * <p>Only presentation is delegated: interaction tokens are minted and
 * consumed by Straja, while the provider receives no authority to run a
 * command, award a reward, mutate a quest, or evaluate a permission.</p>
 */
public final class CustomNpcsNpcSurfaceProvider implements NpcSurfaceProvider {
    private static final Set<NpcCapability> CAPABILITIES = Set.copyOf(EnumSet.of(
            NpcCapability.DIALOGUE,
            NpcCapability.GUI,
            NpcCapability.QUEST_JOURNAL,
            NpcCapability.ACTION_INPUT,
            NpcCapability.PORTRAITS,
            NpcCapability.RICH_TEXT));

    private final NpcSurfaceActionUseCase actions;
    private final NpcSurfaceActionTokenIssuer tokenIssuer;
    private final Consumer<String> diagnostics;
    private final SurfaceResolver surfaceResolver;
    private final NpcProvisioningUseCase provisioning;
    private final Predicate<ServerPlayer> adminTool;
    private final ReflectionBridge bridge;
    private final Map<String, NpcBinding> bindings = new ConcurrentHashMap<>();
    private final Map<String, NpcSurfaceSnapshot> surfaces = new ConcurrentHashMap<>();

    public CustomNpcsNpcSurfaceProvider(
            NpcSurfaceActionUseCase actions,
            NpcSurfaceActionTokenIssuer tokenIssuer,
            Consumer<String> diagnostics) {
        this(actions, tokenIssuer, diagnostics, (playerId, binding, published) -> published, null, null);
    }

    public CustomNpcsNpcSurfaceProvider(
            NpcSurfaceActionUseCase actions,
            NpcSurfaceActionTokenIssuer tokenIssuer,
            Consumer<String> diagnostics,
            SurfaceResolver surfaceResolver) {
        this(actions, tokenIssuer, diagnostics, surfaceResolver, null, null);
    }

    /**
     * Full constructor used by the bootstrap composition root. The admin
     * predicate is deliberately injected so permissions and the physical tool
     * remain Minecraft concerns, while this adapter only renders the GUI.
     */
    public CustomNpcsNpcSurfaceProvider(
            NpcSurfaceActionUseCase actions,
            NpcSurfaceActionTokenIssuer tokenIssuer,
            Consumer<String> diagnostics,
            SurfaceResolver surfaceResolver,
            NpcProvisioningUseCase provisioning,
            Predicate<ServerPlayer> adminTool) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.surfaceResolver = Objects.requireNonNull(surfaceResolver, "surfaceResolver");
        this.provisioning = provisioning;
        this.adminTool = adminTool;
        this.bridge = ReflectionBridge.connect(this::onCustomNpcsEvent, diagnostics);
    }

    @Override
    public NpcProviderId providerId() {
        return NpcProviderId.CUSTOM_NPCS;
    }

    @Override
    public Set<NpcCapability> capabilities() {
        return CAPABILITIES;
    }

    /** True when the supported CustomNPCs API was found and its event hook installed. */
    @Override
    public boolean available() {
        return bridge.available();
    }

    /**
     * Handles the Minecraft attack packet before CustomNPCs decides whether
     * the hit becomes damage. CustomNPCs only emits DamagedEvent after damage
     * is accepted, so the provider needs this earlier boundary for the NPC
     * Wand's admin-only left-click gesture.
     *
     * @return true when this provider owns the admin gesture and the caller
     * should cancel the vanilla attack
     */
    public boolean handleAdminAttack(ServerPlayer player, Entity target) {
        if (!bridge.available()
                || player == null
                || target == null
                || !isCustomNpcsEntity(target)
                || adminTool == null
                || !adminTool.test(player)) {
            return false;
        }
        try {
            Object playerApi = playerApi(player);
            if (playerApi != null) {
                openAdminSelector(playerApi, player, target.getStringUUID());
            } else {
                diagnostics.accept("CustomNPCs admin attack had no player API wrapper");
            }
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs admin selector failed: "
                    + exception.getClass().getSimpleName());
        }
        // Once the operator/tool predicate matched, never let a bridge
        // failure turn the authoring gesture into a real NPC attack.
        return true;
    }

    /** Checks whether a persisted CustomNPC instance can currently be resolved. */
    public NpcProviderResult inspectHost(MinecraftServer server, String hostEntityUuid) {
        Objects.requireNonNull(server, "server");
        if (!bridge.available()) {
            return NpcProviderResult.unavailable("CustomNPCs provider is unavailable");
        }
        UUID hostId;
        try {
            hostId = UUID.fromString(hostEntityUuid);
        } catch (RuntimeException exception) {
            return NpcProviderResult.rejected("invalid-host-identity", "stored host identity is not a UUID");
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(hostId);
            if (entity == null) continue;
            if (!isCustomNpcsEntity(entity)) {
                return NpcProviderResult.rejected(
                        "host-identity-mismatch", "stored UUID currently identifies a non-CustomNPC entity");
            }
            return NpcProviderResult.accepted("loaded in " + level.dimension().location()
                    + " at " + entity.blockPosition().toShortString());
        }
        return NpcProviderResult.unavailable(
                "CustomNPC is not loaded in any server level; possible orphan. Assignment was preserved.");
    }

    private Optional<NpcHostLocation> hostLocation(MinecraftServer server, String hostEntityUuid) {
        UUID hostId;
        try {
            hostId = UUID.fromString(hostEntityUuid);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(hostId);
            if (entity != null && isCustomNpcsEntity(entity)) {
                var position = entity.blockPosition();
                return Optional.of(new NpcHostLocation(
                        level.dimension().location().toString(),
                        position.getX(), position.getY(), position.getZ()));
            }
        }
        return Optional.empty();
    }

    @Override
    public NpcProviderResult bind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (!providerId().equals(binding.providerId())) {
            return NpcProviderResult.rejected("wrong-provider", "binding belongs to another provider");
        }
        if (!bridge.available()) {
            return NpcProviderResult.unavailable(
                    "CustomNPCs 1.21.1 API is not loaded or its event bus is unavailable");
        }
        if (!isUuid(binding.hostEntityUuid())) {
            return NpcProviderResult.rejected("invalid-host-identity", "hostEntityUuid must be a UUID");
        }
        for (NpcBinding current : bindings.values()) {
            if (current.hostEntityUuid().equals(binding.hostEntityUuid())
                    && !current.bindingId().equals(binding.bindingId())) {
                return NpcProviderResult.rejected(
                        "host-already-bound", "one CustomNPCs entity cannot own two logical bindings");
            }
        }
        NpcBinding existing = bindings.putIfAbsent(binding.bindingId(), binding);
        if (existing != null) {
            return existing.equals(binding)
                    ? NpcProviderResult.accepted("CustomNPCs binding already owned")
                    : NpcProviderResult.rejected("already-bound", "binding mapping differs");
        }
        return NpcProviderResult.accepted("CustomNPCs binding created");
    }

    @Override
    public NpcProviderResult unbind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (!bindings.remove(binding.bindingId(), binding)) {
            return NpcProviderResult.rejected("not-bound", "CustomNPCs binding is not owned");
        }
        surfaces.remove(binding.bindingId());
        return NpcProviderResult.accepted("CustomNPCs binding removed");
    }

    @Override
    public NpcProviderResult publish(NpcSurfaceSnapshot surface) {
        Objects.requireNonNull(surface, "surface");
        NpcBinding binding = surface.binding();
        if (!providerId().equals(binding.providerId())) {
            return NpcProviderResult.rejected("wrong-provider", "surface belongs to another provider");
        }
        if (!bridge.available()) {
            return NpcProviderResult.unavailable("CustomNPCs presentation API is unavailable");
        }
        if (!capabilities().containsAll(surface.requiredCapabilities())) {
            return NpcProviderResult.rejected(
                    "unsupported-capability", "CustomNPCs provider lacks a required surface capability");
        }
        if (!binding.equals(bindings.get(binding.bindingId()))) {
            return NpcProviderResult.rejected("not-bound", "surface binding is not registered");
        }
        surfaces.put(binding.bindingId(), surface);
        return NpcProviderResult.accepted("CustomNPCs surface published");
    }

    @Override
    public NpcProviderResult reconcile(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        NpcBinding current = bindings.get(binding.bindingId());
        if (current == null) {
            return NpcProviderResult.rejected("not-bound", "CustomNPCs does not own the binding");
        }
        if (!current.equals(binding)) {
            return NpcProviderResult.rejected("wrong-binding", "CustomNPCs binding mapping differs");
        }
        return NpcProviderResult.accepted("CustomNPCs provider confirms in-process ownership");
    }

    private void onCustomNpcsEvent(Object event) {
        if (!isSupportedInteractionEvent(event)) {
            return;
        }
        try {
            Object npc = field(event, "npc");
            String hostUuid = String.valueOf(invoke(npc, "getUUID"));
            Object playerApi = playerApi(event);
            Object rawPlayer = playerApi == null ? null : invoke(playerApi, "getMCEntity");
            if (!(rawPlayer instanceof ServerPlayer player)) {
                return;
            }

            if (provisioning != null && adminTool != null && adminTool.test(player)) {
                openAdminSelector(playerApi, player, hostUuid);
                cancel(event);
                return;
            }

            // DamagedEvent is registered only to make the NPC wand's
            // left-click gesture possible. Ordinary players keep the native
            // CustomNPCs attack behavior; player-facing Straja surfaces open
            // from InteractEvent instead.
            if (isDamagedEvent(event)) return;

            NpcBinding binding = bindings.values().stream()
                    .filter(candidate -> candidate.hostEntityUuid().equals(hostUuid))
                    .findFirst()
                    .orElse(null);
            if (binding == null) {
                // Unbound CustomNPCs retain their native behavior.
                return;
            }
            NpcSurfaceSnapshot published = surfaces.get(binding.bindingId());
            if (published == null) {
                // A binding without a published Straja surface must not fall
                // through into an unexpected native interaction.
                cancel(event);
                return;
            }
            NpcSurfaceSnapshot surface = surfaceResolver.resolve(player.getUUID(), binding, published);
            if (surface == null) {
                cancel(event);
                return;
            }
            openSurface(playerApi, player, binding, surface);
            cancel(event);
        } catch (RuntimeException exception) {
            try {
                // A provider bridge failure must not fall through from an
                // already intercepted CustomNPCs event into an unintended
                // native action or attack.
                cancel(event);
            } catch (RuntimeException cancelFailure) {
                diagnostics.accept("CustomNPCs event could not be cancelled after bridge failure");
            }
            diagnostics.accept("CustomNPCs interaction ignored after bridge error: "
                    + exception.getClass().getSimpleName());
        }
    }

    private static boolean isSupportedInteractionEvent(Object event) {
        String name = event.getClass().getName();
        return name.equals("noppes.npcs.api.event.NpcEvent$InteractEvent")
                || name.equals("noppes.npcs.api.event.NpcEvent$DamagedEvent");
    }

    private static boolean isDamagedEvent(Object event) {
        return event.getClass().getName().endsWith("NpcEvent$DamagedEvent");
    }

    private static boolean isCustomNpcsEntity(Entity entity) {
        return entity != null && entity.getClass().getName().startsWith("noppes.npcs.");
    }

    private static Object playerApi(Object event) {
        String name = event.getClass().getName();
        Object actor = field(event, name.endsWith("DamagedEvent") ? "source" : "player");
        if (actor == null) return null;
        try {
            return invoke(actor, "getMCEntity") == null ? null : actor;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Object playerApi(ServerPlayer player) {
        return invoke(bridge.api(), "getIEntity", player);
    }

    private void openSurface(
            Object playerApi,
            ServerPlayer player,
            NpcBinding binding,
            NpcSurfaceSnapshot surface) {
        // CustomNPCs orders this factory as id, width, height, pause, player.
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod(binding.bindingId().hashCode(), 20_000) + 1_000,
                421,
                320,
                false,
                playerApi);
        invoke(gui, "addLabel", 1, surface.title(), 12, 8, 396, 20);
        Object body = invoke(gui, "addTextArea", 2, 12, 32, 396, 58);
        invoke(body, "setText", surface.body());
        invoke(body, "setEnabled", false);

        Object choiceHost = gui;
        int choiceX = 12;
        int choiceWidth = 190;
        int y = 98;
        try {
            Object scrollingPanel = invoke(gui, "getScrollingPanel");
            invoke(scrollingPanel, "init", 12, 98, 195, 178);
            choiceHost = scrollingPanel;
            choiceX = 0;
            choiceWidth = 190;
            y = 0;
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs scrolling panel unavailable; using bounded fallback layout");
        }
        for (NpcSurfaceSnapshot.DialogueNode node : surface.dialogue()) {
            invoke(choiceHost, "addLabel", 100 + y, node.text(), choiceX, y, choiceWidth, 20);
            y += 22;
            for (NpcSurfaceSnapshot.Choice choice : node.choices()) {
                NpcSurfaceAction action = action(surface, choice.actionId());
                Object button = invoke(
                        choiceHost, "addButton", 1_000 + y, choice.label(), choiceX, y, choiceWidth, 20);
                invoke(button, "setEnabled", choice.enabled() && action.enabled());
                if (choice.enabled() && action.enabled()) {
                    setButtonHandler(button, gui, binding, surface, action);
                }
                y += 23;
            }
        }
        if (!surface.quests().isEmpty()) {
            invoke(gui, "addLabel", 700, "Quest journal", 218, 98, 190, 20);
            int questY = 121;
            for (NpcSurfaceSnapshot.QuestEntry quest : surface.quests()) {
                invoke(gui, "addLabel", 701 + questY, quest.title() + " — " + quest.state(),
                        218, questY, 190, 20);
                questY += 22;
            }
        }
        invoke(playerApi, "showCustomGui", gui);
    }

    /** Admin-only provisioning surface: select or replace the profile bound to this host NPC. */
    private void openAdminSelector(Object playerApi, ServerPlayer player, String hostUuid) {
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod(("provision:" + hostUuid).hashCode(), 20_000) + 20_000,
                421,
                320,
                false,
                playerApi);
        invoke(gui, "addLabel", 1, "Straja NPC provisioning", 12, 8, 396, 20);

        String displayedRevision = provisioning.assignmentRevision(hostUuid);
        var assignments = provisioning.assignments(providerId(), hostUuid);
        boolean duplicateAssignments = assignments.size() > 1;
        var current = provisioning.current(providerId(), hostUuid);
        var assignmentStatus = provisioning.status(providerId(), hostUuid);
        boolean recoveryPending = assignmentStatus
                .map(status -> "UNKNOWN".equals(status.lifecycleState()))
                .orElse(false);
        String currentText = current
                .map(assignment -> "Current: " + assignment.profileId()
                        + " · role " + assignment.roleId()
                        + (recoveryPending ? " · provider recovery pending" : ""))
                .orElse("Current: unassigned");
        invoke(gui, "addLabel", 2, currentText, 12, 31, 396, 20);
        if (duplicateAssignments) {
            invoke(gui, "addLabel", 3,
                    "Multiple durable bindings found. Resolve one exact binding before editing the profile.",
                    12, 50, 396, 20);
        }

        Object profileHost = gui;
        int profileX = 12;
        int profileWidth = 396;
        int y = 58;
        try {
            Object scrollingPanel = invoke(gui, "getScrollingPanel");
            invoke(scrollingPanel, "init", 12, duplicateAssignments ? 76 : 58, 396,
                    duplicateAssignments ? 132 : 150);
            profileHost = scrollingPanel;
            profileX = 0;
            profileWidth = 396;
            y = 0;
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs provisioning scroll unavailable; using bounded fallback layout");
        }

        int buttonId = 2_000;
        for (NpcProvisioningUseCase.ProfileOption option : provisioning.profiles(providerId())) {
            boolean selected = current.map(assignment -> assignment.profileId().equals(option.profileId()))
                    .orElse(false);
            String label = profileOptionLabel(option, selected, recoveryPending);
            Object button = invoke(
                    profileHost,
                    "addButton",
                    buttonId++,
                    label,
                    profileX,
                    y,
                    profileWidth,
                    22);
            invoke(button, "setHoverText", (Object) profileOptionHoverText(option, recoveryPending));
            invoke(button, "setEnabled", option.enabled() && !recoveryPending && !duplicateAssignments);
            if (option.enabled() && !recoveryPending && !duplicateAssignments) {
                setAdminProfileHandler(button, gui, playerApi, player, hostUuid, option, displayedRevision);
            }
            y += 25;
        }

        if (current.isPresent()) {
            // Keep this below the compact profile panel; the lower rows of the
            // logical 320px GUI are outside a normal 240px client viewport
            // after CustomNPCs applies its negative top offset.
            Object status = invoke(gui, "addButton", 9_000, "Status", 12, 214, 120, 22);
            setAdminStatusHandler(status, gui, playerApi, player, hostUuid);
            if (!recoveryPending && !duplicateAssignments) {
                Object reproject = invoke(gui, "addButton", 9_001, "Re-sync profile", 138, 214, 132, 22);
                setAdminReprojectHandler(reproject, gui, playerApi, player, hostUuid);
            }
            String pendingOperation = assignmentStatus
                    .map(NpcProvisioningUseCase.AssignmentStatus::pendingOperation).orElse("");
            String unassignLabel = duplicateAssignments
                    ? "Resolve duplicates" : unassignButtonLabel(recoveryPending, pendingOperation);
            Object unassign = invoke(gui, "addButton", 9_002, unassignLabel, 276, 214, 132, 22);
            if (duplicateAssignments) {
                setAdminDuplicateCleanupHandler(unassign, gui, playerApi, player, hostUuid, displayedRevision);
            } else {
                setAdminUnassignHandler(unassign, gui, playerApi, player, hostUuid, displayedRevision);
            }
        }
        invoke(playerApi, "showCustomGui", gui);
    }

    private void openAdminDuplicateCleanup(
            Object playerApi,
            ServerPlayer player,
            String hostUuid,
            String expectedRevision) {
        var assignments = provisioning.assignments(providerId(), hostUuid);
        Object gui = invoke(
                bridge.api(), "createCustomGui",
                Math.floorMod(("duplicates:" + hostUuid).hashCode(), 20_000) + 120_000,
                421,
                320,
                false,
                playerApi);
        invoke(gui, "addLabel", 1, "Resolve duplicate NPC bindings", 12, 8, 396, 20);
        invoke(gui, "addLabel", 2,
                "Select the exact durable binding to unassign. Other bindings remain untouched.",
                12, 32, 396, 32);
        Object host = gui;
        int y = 70;
        try {
            Object scrollingPanel = invoke(gui, "getScrollingPanel");
            invoke(scrollingPanel, "init", 12, 70, 396, 150);
            host = scrollingPanel;
            y = 0;
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs duplicate-binding scroll unavailable; using bounded fallback layout");
        }
        int buttonId = 12_000;
        for (NpcProvisioningUseCase.AssignmentView assignment : assignments) {
            Object button = invoke(host, "addButton", buttonId++,
                    assignment.profileId() + " · " + assignment.bindingId(),
                    host == gui ? 12 : 0, y, 396, 22);
            invoke(button, "setHoverText", "Provider: " + assignment.providerId()
                    + "\nRole/station: " + assignment.roleId() + " / " + assignment.stationId());
            setAdminDuplicateBindingHandler(button, gui, playerApi, player, hostUuid,
                    assignment.bindingId(), expectedRevision);
            y += 25;
        }
        Object back = invoke(gui, "addButton", 12_900, "Back", 218, 240, 190, 22);
        setAdminCancelHandler(back, gui, playerApi, player, hostUuid);
        invoke(playerApi, "showCustomGui", gui);
    }

    private void openAdminConfirmation(
            Object playerApi,
            ServerPlayer player,
            String hostUuid,
            NpcProvisioningUseCase.ProfileOption option,
            String expectedRevision) {
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod(("confirm:" + hostUuid + option.profileId()).hashCode(), 20_000) + 40_000,
                421,
                320,
                false,
                playerApi);
        invoke(gui, "addLabel", 1, "Confirm NPC profile", 12, 8, 396, 20);
        Object profileLabel = invoke(
                gui, "addLabel", 2,
                option.title() + " · " + option.profileId() + " · " + option.roleId(),
                12, 32, 396, 20);
        invoke(profileLabel, "setHoverText", option.summary());
        // Keep the action row inside the 240px client viewport. CustomNPCs
        // reports the logical GUI as 421x320, but a normal client viewport
        // opens it with a negative top offset; a y=220 button is therefore
        // not reliably reachable by a player-facing authoring tool.
        Object referenceHost = gui;
        int referenceX = 12;
        int referenceY = 58;
        try {
            Object scrollingPanel = invoke(gui, "getScrollingPanel");
            invoke(scrollingPanel, "init", 12, 58, 396, 78);
            referenceHost = scrollingPanel;
            referenceX = 0;
            referenceY = 0;
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs confirmation scroll unavailable; using bounded reference summary");
            Object summary = invoke(gui, "addTextArea", 3, 12, 58, 396, 76);
            invoke(summary, "setText", String.join("\n", profileReferenceRows(option)));
            invoke(summary, "setHoverText", (Object) profileReferenceRows(option).toArray(String[]::new));
            invoke(summary, "setEnabled", false);
        }
        if (referenceHost != gui) {
            int rowId = 10_000;
            for (String row : profileReferenceRows(option)) {
                invoke(referenceHost, "addLabel", rowId++, row, referenceX, referenceY, 396, 18);
                referenceY += 18;
            }
        }
        Object confirm = invoke(gui, "addButton", 9_100, "Assign profile", 12, 150, 190, 22);
        Object cancel = invoke(gui, "addButton", 9_101, "Cancel", 218, 150, 190, 22);
        setAdminConfirmHandler(confirm, gui, playerApi, player, hostUuid, option, expectedRevision);
        setAdminCancelHandler(cancel, gui, playerApi, player, hostUuid);
        invoke(playerApi, "showCustomGui", gui);
    }

    static String profileOptionLabel(
            NpcProvisioningUseCase.ProfileOption option,
            boolean selected,
            boolean recoveryPending) {
        String label = option.title() + " · " + option.profileId() + " · " + option.roleId();
        if (selected) label += " (current)";
        if (!option.enabled() || recoveryPending) label += " · unavailable";
        return label;
    }

    static String[] profileOptionHoverText(
            NpcProvisioningUseCase.ProfileOption option,
            boolean recoveryPending) {
        String availability = recoveryPending
                ? "Unavailable: provider recovery is pending"
                : option.enabled() ? "Available"
                : "Unavailable: " + option.disabledReason();
        return new String[] {
                "Profile ID: " + option.profileId(),
                "Purpose: " + option.summary(),
                "Role/station: " + option.roleId() + " / " + option.stationId(),
                "Availability: " + availability,
                "Wired content: " + option.dialogueContentIds().size() + " dialogue nodes, "
                        + option.questContentIds().size() + " quests, "
                        + option.actionContentIds().size() + " actions"
        };
    }

    static String unassignButtonLabel(boolean recoveryPending, String pendingOperation) {
        if (!recoveryPending) return "Unassign";
        return "UNBIND".equals(pendingOperation) ? "Retry unassign" : "Cancel pending";
    }

    static java.util.List<String> profileReferenceRows(NpcProvisioningUseCase.ProfileOption option) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        addReferenceRows(rows, "Dialogue nodes", option.dialogueContentIds());
        addReferenceRows(rows, "Quests", option.questContentIds());
        addReferenceRows(rows, "Actions", option.actionContentIds());
        return java.util.List.copyOf(rows);
    }

    private static void addReferenceRows(
            java.util.List<String> rows,
            String label,
            java.util.List<NpcContentId> ids) {
        rows.add(label + " (" + ids.size() + "):");
        if (ids.isEmpty()) {
            rows.add("  none");
            return;
        }
        ids.stream().map(id -> "  " + id.value()).forEach(rows::add);
    }

    private void openAdminUnassignConfirmation(
            Object playerApi,
            ServerPlayer player,
            String hostUuid,
            String bindingId,
            String expectedRevision,
            boolean recoveryPending) {
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod(("unassign:" + hostUuid).hashCode(), 20_000) + 60_000,
                421,
                320,
                false,
                playerApi);
        invoke(gui, "addLabel", 1,
                recoveryPending ? "Cancel pending NPC assignment" : "Remove Straja profile",
                12, 8, 396, 20);
        invoke(gui, "addLabel", 2, recoveryPending
                ? "Straja will remove this assignment only after the provider confirms it is unbound."
                : "This NPC will return to native CustomNPCs behavior.",
                12, 38, 396, 38);
        Object confirm = invoke(gui, "addButton", 9_200,
                recoveryPending ? "Confirm cancellation" : "Unassign", 12, 150, 190, 22);
        Object cancel = invoke(gui, "addButton", 9_201, "Cancel", 218, 150, 190, 22);
        setAdminUnassignConfirmHandler(confirm, gui, playerApi, player, hostUuid, bindingId, expectedRevision);
        setAdminCancelHandler(cancel, gui, playerApi, player, hostUuid);
        invoke(playerApi, "showCustomGui", gui);
    }

    private void openAdminStatus(Object playerApi, ServerPlayer player, String hostUuid) {
        NpcProvisioningUseCase.AssignmentStatus status = provisioning
                .status(providerId(), hostUuid).orElse(null);
        if (status == null) {
            openAdminSelector(playerApi, player, hostUuid);
            return;
        }
        NpcProviderResult host = inspectHost(player.getServer(), hostUuid);
        var assignment = status.assignment();
        Object gui = invoke(
                bridge.api(), "createCustomGui",
                Math.floorMod(("status:" + hostUuid).hashCode(), 20_000) + 80_000,
                421, 320, false, playerApi);
        invoke(gui, "addLabel", 1, "NPC assignment status", 12, 8, 396, 20);
        Object details = invoke(gui, "addTextArea", 2, 12, 34, 396, 190);
        String projectionError = status.lastProjectionError().isBlank()
                ? "none" : status.lastProjectionError();
        String pending = status.pendingOperation().isBlank() ? "none" : status.pendingOperation();
        String text = "Provider: " + assignment.providerId()
                + "\nProvider instance: " + assignment.hostEntityUuid()
                + "\nBinding: " + assignment.bindingId()
                + "\nProfile: " + assignment.profileId() + " (schema v" + assignment.schemaVersion() + ")"
                + "\nLast-known location: " + (assignment.hostLocation() == null
                        ? "unknown" : assignment.hostLocation().displayValue())
                + "\nRole/station: " + assignment.roleId() + " / " + assignment.stationId()
                + "\nLifecycle: " + status.lifecycleState() + " (pending " + pending + ")"
                + "\nAssignment set by: " + (assignment.assignedBy().isBlank() ? "unknown" : assignment.assignedBy())
                + "\nAssignment set at: " + formatTimestamp(assignment.assignedAtEpochMillis())
                + "\nProvider host: " + host.status() + " — " + host.message()
                + "\nLast projection error: " + projectionError;
        invoke(details, "setText", text);
        invoke(details, "setEnabled", false);
        Object audit = invoke(gui, "addButton", 9_100, "Audit history", 12, 240, 190, 22);
        Object back = invoke(gui, "addButton", 9_101, "Back", 218, 240, 190, 22);
        setAdminAuditHandler(audit, gui, playerApi, player, hostUuid);
        setAdminCancelHandler(back, gui, playerApi, player, hostUuid);
        invoke(playerApi, "showCustomGui", gui);
    }

    private void openAdminAudit(Object playerApi, ServerPlayer player, String hostUuid) {
        NpcProviderId auditProvider = provisioning.current(providerId(), hostUuid)
                .map(assignment -> NpcProviderId.of(assignment.providerId()))
                .orElse(providerId());
        var events = provisioning.auditTrail(auditProvider, hostUuid);
        Object gui = invoke(
                bridge.api(), "createCustomGui",
                Math.floorMod(("audit:" + hostUuid).hashCode(), 20_000) + 100_000,
                421, 320, false, playerApi);
        invoke(gui, "addLabel", 1, "Provisioning audit", 12, 8, 396, 20);
        Object details = invoke(gui, "addTextArea", 2, 12, 34, 396, 190);
        String history = events.isEmpty() ? "No provisioning events recorded."
                : events.stream().skip(Math.max(0, events.size() - 10L))
                        .map(event -> formatTimestamp(event.occurredAtEpochMillis())
                                + " | " + event.action() + " | " + event.actorId()
                                + " | " + (event.oldProfileId().isBlank() ? "(none)" : event.oldProfileId())
                                + " -> " + (event.newProfileId().isBlank() ? "(none)" : event.newProfileId())
                                + " | " + event.outcome() + "/" + event.resultCode()
                                + (event.failureReason().isBlank() ? "" : " | " + event.failureReason()))
                        .collect(java.util.stream.Collectors.joining("\n"));
        invoke(details, "setText", history);
        invoke(details, "setEnabled", false);
        Object back = invoke(gui, "addButton", 9_102, "Back to status", 12, 240, 190, 22);
        setGuiCallback(back, args -> {
            Object clickedGui = callbackGui(args, gui);
            Object currentPlayerApi = guiPlayer(clickedGui, playerApi);
            ServerPlayer currentPlayer = serverPlayer(currentPlayerApi, player);
            if (!isAdminToolAuthorized(currentPlayer)) {
                showAuthorizationResult(clickedGui, currentPlayer);
                return;
            }
            openAdminStatus(currentPlayerApi, currentPlayer, hostUuid);
        });
        invoke(playerApi, "showCustomGui", gui);
    }

    private void setAdminStatusHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            openAdminStatus(playerApi, player, hostUuid);
        });
    }

    private void setAdminAuditHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            openAdminAudit(playerApi, player, hostUuid);
        });
    }

    private void setAdminReprojectHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            NpcProviderResult host = inspectHost(player.getServer(), hostUuid);
            if (host.status() != NpcProviderResult.Status.ACCEPTED) {
                showProvisioningResult(clickedGui, player, provisioningResult(host));
                return;
            }
            NpcProvisioningUseCase.ProvisioningResult result = provisioning.reproject(
                    providerId(), hostUuid, player.getUUID().toString());
            if (result.status() == NpcProvisioningUseCase.Status.ACCEPTED) {
                openAdminSelector(playerApi, player, hostUuid);
            } else {
                showProvisioningResult(clickedGui, player, result);
            }
        });
    }

    private void setAdminProfileHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid,
            NpcProvisioningUseCase.ProfileOption option,
            String expectedRevision) {
        setGuiCallback(button, (args) -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            openAdminConfirmation(playerApi, player, hostUuid, option, expectedRevision);
        });
    }

    private void setAdminUnassignHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid,
            String expectedRevision) {
        setGuiCallback(button, (args) -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            boolean recoveryPending = provisioning.status(providerId(), hostUuid)
                    .map(status -> "UNKNOWN".equals(status.lifecycleState())).orElse(false);
            String bindingId = provisioning.current(providerId(), hostUuid)
                    .map(NpcProvisioningUseCase.AssignmentView::bindingId).orElse("");
            openAdminUnassignConfirmation(playerApi, player, hostUuid, bindingId,
                    expectedRevision, recoveryPending);
        });
    }

    private void setAdminDuplicateCleanupHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid,
            String expectedRevision) {
        setGuiCallback(button, (args) -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            openAdminDuplicateCleanup(playerApi, player, hostUuid, expectedRevision);
        });
    }

    private void setAdminDuplicateBindingHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid,
            String bindingId,
            String expectedRevision) {
        setGuiCallback(button, (args) -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            openAdminUnassignConfirmation(playerApi, player, hostUuid, bindingId,
                    expectedRevision, false);
        });
    }

    private void setAdminConfirmHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid,
            NpcProvisioningUseCase.ProfileOption option,
            String expectedRevision) {
        setGuiCallback(button, (args) -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            NpcProviderResult host = inspectHost(player.getServer(), hostUuid);
            if (host.status() != NpcProviderResult.Status.ACCEPTED) {
                showProvisioningResult(clickedGui, player, provisioningResult(host));
                return;
            }
            NpcProvisioningUseCase.ProvisioningResult result = provisioning.assignIfRevisionMatches(
                    providerId(), hostUuid, player.getUUID().toString(), option.profileId(),
                    hostLocation(player.getServer(), hostUuid), expectedRevision);
            if (result.status() == NpcProvisioningUseCase.Status.ACCEPTED) {
                openAdminSelector(playerApi, player, hostUuid);
            } else {
                showProvisioningResult(clickedGui, player, result);
            }
        });
    }

    private void setAdminUnassignConfirmHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid,
            String bindingId,
            String expectedRevision) {
        setGuiCallback(button, (args) -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            NpcProvisioningUseCase.ProvisioningResult result = provisioning.unassignBindingIfRevisionMatches(
                    providerId(), hostUuid, bindingId, player.getUUID().toString(), expectedRevision);
            if (result.status() == NpcProvisioningUseCase.Status.ACCEPTED) {
                openAdminSelector(playerApi, player, hostUuid);
            } else {
                showProvisioningResult(clickedGui, player, result);
            }
        });
    }

    private void setAdminCancelHandler(
            Object button,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            String hostUuid) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, parentGui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminToolAuthorized(player)) {
                showAuthorizationResult(clickedGui, player);
                return;
            }
            openAdminSelector(playerApi, player, hostUuid);
        });
    }

    private void setGuiCallback(Object button, java.util.function.Consumer<Object[]> callback) {
        try {
            Class<?> callbackType = Class.forName(
                    "noppes.npcs.api.function.gui.GuiComponentClicked",
                    true,
                    button.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onClick")) callback.accept(args == null ? new Object[0] : args);
                return null;
            };
            Object callbackProxy = Proxy.newProxyInstance(
                    callbackType.getClassLoader(), new Class<?>[] {callbackType}, handler);
            invoke(button, "setOnPress", callbackProxy);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("CustomNPCs GUI callback API is missing", exception);
        }
    }

    private static Object callbackGui(Object[] args, Object fallback) {
        if (args.length > 0 && args[0] != null) return args[0];
        return fallback;
    }

    private static Object guiPlayer(Object gui, Object fallback) {
        try {
            return invoke(gui, "getPlayer");
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static ServerPlayer serverPlayer(Object playerApi, ServerPlayer fallback) {
        try {
            Object raw = invoke(playerApi, "getMCEntity");
            return raw instanceof ServerPlayer player ? player : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean isAdminToolAuthorized(ServerPlayer player) {
        return player != null && adminTool != null && adminTool.test(player);
    }

    private static NpcProvisioningUseCase.ProvisioningResult provisioningResult(NpcProviderResult result) {
        return switch (result.status()) {
            case ACCEPTED, RECONCILED -> NpcProvisioningUseCase.ProvisioningResult.accepted(result.message());
            case REJECTED -> NpcProvisioningUseCase.ProvisioningResult.rejected(result.code(), result.message());
            case UNAVAILABLE -> NpcProvisioningUseCase.ProvisioningResult.unavailable(result.message());
            case UNKNOWN -> NpcProvisioningUseCase.ProvisioningResult.unknown(result.message());
        };
    }

    private static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0) return "unknown";
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(epochMillis));
    }

    private void showAuthorizationResult(Object gui, ServerPlayer player) {
        showProvisioningResult(gui, player, NpcProvisioningUseCase.ProvisioningResult.rejected(
                "admin-authorization-required",
                "NPC Tool permission and held wand are required for this action."));
    }

    private void showProvisioningResult(
            Object gui,
            ServerPlayer player,
            NpcProvisioningUseCase.ProvisioningResult result) {
        if (player != null) {
            try {
                Class<?> component = Class.forName(
                        "net.minecraft.network.chat.Component", true, player.getClass().getClassLoader());
                Object message = component.getMethod("literal", String.class)
                        .invoke(null, "[Straja NPC] " + result.message());
                invoke(player, "sendSystemMessage", message);
            } catch (ReflectiveOperationException | RuntimeException error) {
                diagnostics.accept("Could not send NPC provisioning result to player chat: "
                        + error.getClass().getSimpleName());
            }
        }
        try {
            invoke(gui, "removeComponent", 9_999);
        } catch (RuntimeException ignored) {
            // Optional component replacement varies between CustomNPCs builds.
        }
        invoke(gui, "addLabel", 9_999, result.message(), 12, 178, 396, 40);
        invoke(gui, "update");
    }

    private void setButtonHandler(
            Object button,
            Object gui,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcSurfaceAction action) {
        try {
            Class<?> callbackType = Class.forName(
                    "noppes.npcs.api.function.gui.GuiComponentClicked",
                    true,
                    button.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onClick") && args != null && args.length == 2) {
                    if (action.inputs().isEmpty()) {
                        submitAction(args[0], binding, surface, action.actionId(), Map.of());
                    } else {
                        openInputGui(args[0], binding, surface, action);
                    }
                }
                return null;
            };
            Object callback = Proxy.newProxyInstance(
                    callbackType.getClassLoader(), new Class<?>[] {callbackType}, handler);
            invoke(button, "setOnPress", callback);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("CustomNPCs GUI callback API is missing", exception);
        }
    }

    private void openInputGui(
            Object parentGui,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcSurfaceAction action) {
        Object playerApi = invoke(parentGui, "getPlayer");
        // Keep the provider-specific factory order explicit at this boundary.
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod((binding.bindingId() + action.actionId().value()).hashCode(), 20_000) + 1_000,
                422,
                320,
                false,
                playerApi);
        invoke(gui, "addLabel", 1, action.label(), 12, 8, 396, 20);
        Map<String, Integer> fieldIds = new java.util.LinkedHashMap<>();
        Object fieldHost = gui;
        int fieldX = 12;
        int fieldWidth = 396;
        int y = 42;
        try {
            Object scrollingPanel = invoke(gui, "getScrollingPanel");
            invoke(scrollingPanel, "init", 12, 42, 396, 205);
            fieldHost = scrollingPanel;
            fieldX = 0;
            fieldWidth = 396;
            y = 0;
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs input scrolling panel unavailable; using bounded fallback layout");
        }
        int nextId = 2;
        for (NpcSurfaceAction.InputField field : action.inputs()) {
            if (field.visible()) {
                invoke(fieldHost, "addLabel", 10_000 + nextId, field.label(), fieldX, y, fieldWidth, 18);
            }
            Object textField = invoke(fieldHost, "addTextField", nextId, fieldX, y + 19, fieldWidth, 20);
            if (!field.initialValue().isEmpty()) {
                invoke(textField, "setText", field.initialValue());
            }
            if (!field.visible()) {
                invoke(textField, "setVisible", false);
            }
            fieldIds.put(field.key(), nextId++);
            y += field.visible() ? 66 : 2;
        }
        Object submit = invoke(gui, "addButton", 9_500, "Submit", 12, Math.min(y, 270), 190, 22);
        setInputButtonHandler(submit, fieldHost, binding, surface, action, fieldIds);
        invoke(playerApi, "showCustomGui", gui);
    }

    private void setInputButtonHandler(
            Object button,
            Object fieldHost,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcSurfaceAction action,
            Map<String, Integer> fieldIds) {
        try {
            Class<?> callbackType = Class.forName(
                    "noppes.npcs.api.function.gui.GuiComponentClicked",
                    true,
                    button.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onClick") && args != null && args.length == 2) {
                    submitInputAction(args[0], fieldHost, binding, surface, action, fieldIds);
                }
                return null;
            };
            Object callback = Proxy.newProxyInstance(
                    callbackType.getClassLoader(), new Class<?>[] {callbackType}, handler);
            invoke(button, "setOnPress", callback);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("CustomNPCs GUI callback API is missing", exception);
        }
    }

    private void submitInputAction(
            Object gui,
            Object fieldHost,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcSurfaceAction action,
            Map<String, Integer> fieldIds) {
        Map<String, String> input = new java.util.LinkedHashMap<>();
        for (NpcSurfaceAction.InputField field : action.inputs()) {
            Object component = invoke(fieldHost, "getComponent", fieldIds.get(field.key()));
            String value = String.valueOf(invoke(component, "getText"));
            if (value.length() > field.maxLength()) {
                showResult(gui, new NpcActionResult(
                        NpcActionResult.Status.REJECTED,
                        "input-too-long",
                        field.label() + " exceeds the allowed length."));
                return;
            }
            if (field.required() && value.isBlank()) {
                showResult(gui, new NpcActionResult(
                        NpcActionResult.Status.REJECTED,
                        "input-required",
                        field.label() + " is required."));
                return;
            }
            input.put(field.key(), value);
        }
        submitAction(gui, binding, surface, action.actionId(), input);
    }

    private void submitAction(
            Object gui,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcContentId actionId,
            Map<String, String> input) {
        try {
            Object playerApi = invoke(gui, "getPlayer");
            Object rawPlayer = invoke(playerApi, "getMCEntity");
            if (!(rawPlayer instanceof ServerPlayer player)) {
                return;
            }
            String token = tokenIssuer.issueToken(player.getUUID(), binding, actionId, surface);
            NpcActionResult result = actions.submit(new NpcActionRequest(
                    providerId(), binding.bindingId(), player.getUUID(), actionId, token, input));
            if (result.status() == NpcActionResult.Status.ACCEPTED) {
                NpcSurfaceSnapshot published = surfaces.get(binding.bindingId());
                if (published != null) {
                    openSurface(
                            playerApi,
                            player,
                            binding,
                            surfaceResolver.resolve(player.getUUID(), binding, published));
                    return;
                }
            }
            showResult(gui, result);
        } catch (RuntimeException exception) {
            showResult(gui, new NpcActionResult(
                    NpcActionResult.Status.UNAVAILABLE,
                    "bridge-failed",
                    "This Straja action is temporarily unavailable."));
        }
    }

    @FunctionalInterface
    public interface SurfaceResolver {
        NpcSurfaceSnapshot resolve(
                UUID playerId, NpcBinding binding, NpcSurfaceSnapshot published);
    }

    private static void showResult(Object gui, NpcActionResult result) {
        try {
            invoke(gui, "removeComponent", 9_000);
        } catch (RuntimeException ignored) {
            // Removing a first result label is optional for providers that do
            // not expose component replacement.
        }
        invoke(gui, "addLabel", 9_000,
                result.message(), 12, 286, 396, 24);
        invoke(gui, "update");
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, NpcContentId id) {
        return surface.actions().stream()
                .filter(candidate -> candidate.actionId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("surface action disappeared: " + id.value()));
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Object field(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CustomNPCs event field is missing: " + name, exception);
        }
    }

    private static void cancel(Object event) {
        invoke(event, "setCanceled", true);
    }

    private static Object invoke(Object target, String name, Object... arguments) {
        Objects.requireNonNull(target, "target");
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length
                    || !compatible(method.getParameterTypes(), arguments)) {
                continue;
            }
            try {
                return method.invoke(target, arguments);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("CustomNPCs call failed: " + name, exception);
            }
        }
        throw new IllegalStateException("CustomNPCs method is missing: " + name);
    }

    private static boolean compatible(Class<?>[] parameters, Object[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            if (arguments[i] == null) continue;
            Class<?> parameter = parameters[i];
            Class<?> argument = arguments[i].getClass();
            if (parameter.isPrimitive()) {
                if ((parameter == int.class && argument == Integer.class)
                        || (parameter == boolean.class && argument == Boolean.class)) continue;
                return false;
            }
            if (!parameter.isAssignableFrom(argument)) return false;
        }
        return true;
    }

    static void registerInteractionListener(
            Object eventBus,
            Class<?> eventType,
            Consumer<Object> listener) {
        Method addListener = java.util.Arrays.stream(eventBus.getClass().getMethods())
                .filter(method -> method.getName().equals("addListener")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0].equals(Class.class)
                        && Consumer.class.isAssignableFrom(method.getParameterTypes()[1]))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("CustomNPCs addListener(Class, Consumer) is missing"));
        try {
            addListener.invoke(eventBus, eventType, listener);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CustomNPCs listener registration failed", exception);
        }
    }

    private static final class ReflectionBridge {
        private final Object api;
        private final boolean available;

        private ReflectionBridge(Object api, boolean available) {
            this.api = api;
            this.available = available;
        }

        static ReflectionBridge connect(Consumer<Object> listener, Consumer<String> diagnostics) {
            try {
                Class<?> apiType = Class.forName("noppes.npcs.api.NpcAPI");
                boolean apiAvailable = (Boolean) apiType.getMethod("IsAvailable").invoke(null);
                Object api = apiType.getMethod("Instance").invoke(null);
                if (!apiAvailable || api == null) {
                    diagnostics.accept("CustomNPCs API is present but not available");
                    return new ReflectionBridge(null, false);
                }
                Object eventBus = apiType.getMethod("events").invoke(api);
                Class<?> interactEventType = Class.forName(
                        "noppes.npcs.api.event.NpcEvent$InteractEvent");
                registerInteractionListener(eventBus, interactEventType, listener);
                try {
                    Class<?> damagedEventType = Class.forName(
                            "noppes.npcs.api.event.NpcEvent$DamagedEvent");
                    registerInteractionListener(eventBus, damagedEventType, listener);
                } catch (ClassNotFoundException ignored) {
                    diagnostics.accept("CustomNPCs damaged event is unavailable; admin left-click is disabled");
                }
                return new ReflectionBridge(api, true);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                diagnostics.accept("CustomNPCs API unavailable: "
                        + exception.getClass().getSimpleName());
                return new ReflectionBridge(null, false);
            }
        }

        Object api() {
            if (!available) throw new IllegalStateException("CustomNPCs API unavailable");
            return api;
        }

        boolean available() {
            return available;
        }
    }
}
