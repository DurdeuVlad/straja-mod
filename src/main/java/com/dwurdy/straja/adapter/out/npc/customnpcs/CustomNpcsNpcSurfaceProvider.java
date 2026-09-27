package com.dwurdy.straja.adapter.out.npc.customnpcs;

import com.dwurdy.straja.adapter.out.theme.GuiTheme;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
import net.minecraft.core.registries.BuiltInRegistries;

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
    private static final int FALLBACK_PROFILE_PAGE_SIZE = 5;
    private static final int GUI_WIDTH = 421;
    private static final int ADMIN_GUI_HEIGHT = GuiTheme.GUI_HEIGHT;
    // CustomNPCs translates rendered components on Z by their component id,
    // so glyphs must use low ids that stay inside the projection depth
    // range; these bands are free in every surface's id map.
    private static final int HEADER_ICON_ID = 96;
    private static final int HEADER_TEXTURE_ICON_ID = 98;
    private static final int HEADER_RULE_ID = 97;
    private static final int PORTRAIT_ID = 95;
    // Rendered components translate on Z by id, so an icon must carry a
    // higher id than the button it decorates or the button occludes it
    // (verified live: ids below the row buttons drew nothing). Keep every
    // icon id above its button band but far below the ~1000+ clip risk zone
    // observed at 95_000.
    private static final int ROW_GLYPH_BASE = 300;
    private static final int PROFILE_GLYPH_BASE = 2_100;
    private static final int FOOTER_GLYPH_BASE = 9_100;
    private static final int INLINE_ICON_ID = 9_600;
    private static final int QUEST_GLYPH_BASE = 70;
    private static final int CHOICE_PAGE_SIZE = 3;
    private static final int[] CHOICE_SLOT_Y = {134, 158, 182};
    private static final int PAGER_PREV_ID = 9_520;
    private static final int PAGER_NEXT_ID = 9_521;
    private static final int PAGER_LABEL_ID = 9_522;
    // CustomNPCs labels draw on a single line and overflow their width;
    // these budgets keep wrapped lines inside their columns.
    private static final int NARROW_LABEL_CHARS = 30;
    private static final int QUEST_LABEL_CHARS = 27;
    private static final int RESULT_LABEL_CHARS = 56;
    private static final int WIDE_LABEL_CHARS = 62;
    private static final int TITLE_CHARS = 56;

    private final NpcSurfaceActionUseCase actions;
    private final NpcSurfaceActionTokenIssuer tokenIssuer;
    private final Consumer<String> diagnostics;
    private final SurfaceResolver surfaceResolver;
    private final NpcProvisioningUseCase provisioning;
    private final Predicate<ServerPlayer> adminTool;
    private final ReflectionBridge bridge;
    private final Map<String, NpcBinding> bindings = new ConcurrentHashMap<>();
    private final Map<String, NpcSurfaceSnapshot> surfaces = new ConcurrentHashMap<>();
    private final CustomNpcAdminAttackGate adminAttackGate = new CustomNpcAdminAttackGate();

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
        if (player == null || target == null || adminTool == null) {
            return false;
        }
        boolean authorized = adminTool.test(player);
        boolean liveTarget = !target.isRemoved()
                && target.level() == player.serverLevel()
                && player.serverLevel().getEntity(target.getUUID()) == target;
        var decision = adminAttackGate.decide(player.getUUID(), target.getUUID(),
                String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType())),
                liveTarget, authorized, bridge.available(), player.level().getGameTime());
        if (decision.action() == CustomNpcAdminAttackGate.Action.PASS) return false;
        if (decision.action() == CustomNpcAdminAttackGate.Action.CANCEL) {
            if (authorized && !bridge.available()) {
                diagnostics.accept("CustomNPCs admin selector is unavailable; NPC attack cancelled");
            }
            return true;
        }
        try {
            Object playerApi = playerApi(player);
            if (playerApi != null) {
                openAdminSelector(playerApi, player, decision.hostId().toString());
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

    public void forgetAdminAttack(UUID playerId) {
        adminAttackGate.forget(playerId);
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
        return event.getClass().getName().equals("noppes.npcs.api.event.NpcEvent$InteractEvent");
    }

    public static boolean isCustomNpcsEntity(Entity entity) {
        return entity != null && CustomNpcAdminAttackGate.CUSTOM_NPC_TYPE.equals(
                String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
    }

    private static Object playerApi(Object event) {
        Object actor = field(event, "player");
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
        openSurface(playerApi, player, binding, surface, 0);
    }

    private void openSurface(
            Object playerApi,
            ServerPlayer player,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            int requestedPage) {
        // CustomNPCs orders this factory as id, width, height, pause, player.
        // 421x240 is the standard surface class (docs §6): the old 320px
        // variant clipped top and bottom bands on small windows.
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod(binding.bindingId().hashCode(), 20_000) + 1_000,
                GUI_WIDTH,
                GuiTheme.GUI_HEIGHT,
                false,
                playerApi);
        addGuiHeader(gui, 1, ellipsize(surface.title(), TITLE_CHARS),
                GuiTheme.roleIconKey(surface.profileId().value()), GuiTheme.COLOR_PAPER_BRIGHT);
        // Id 2 doubles as the result sink: rejected actions rewrite this
        // flavor text with the outcome message (see showResult).
        Object body = invoke(gui, "addTextArea", 2, GuiTheme.MARGIN, GuiTheme.BODY_Y,
                GuiTheme.CONTENT_WIDTH, GuiTheme.BODY_H);
        invoke(body, "setText", surface.body());
        invoke(body, "setEnabled", false);

        // The left column is paged GUI-level content, not a scroll panel:
        // CustomNPCs 1.21.1 scroll wrappers ignore the mouse wheel and never
        // deliver clicks to children below the fold, so off-screen choices
        // would be unreachable. Same treatment as the admin selector.
        int y = GuiTheme.COLUMN_TOP;
        int nodeLines = 0;
        // Choices collect from every node (deduped by actionId — some services
        // publish the same choice list on each node); the 3-line cap truncates
        // label emission only, never action reachability.
        Map<String, NpcSurfaceSnapshot.Choice> choicesByAction = new java.util.LinkedHashMap<>();
        for (NpcSurfaceSnapshot.DialogueNode node : surface.dialogue()) {
            if (nodeLines < 3) {
                List<String> lines = wrapText(node.text(), NARROW_LABEL_CHARS);
                for (int i = 0; i < lines.size() && nodeLines < 3; i++) {
                    String line = lines.get(i);
                    if (nodeLines == 2 && i + 1 < lines.size()) {
                        line = ellipsize(line, NARROW_LABEL_CHARS - 1);
                    }
                    invoke(gui, "addLabel", 100 + y, line, GuiTheme.MARGIN, y,
                            GuiTheme.CHOICE_W - 5, 14);
                    y += 14;
                    nodeLines++;
                }
            }
            for (NpcSurfaceSnapshot.Choice choice : node.choices()) {
                choicesByAction.putIfAbsent(choice.actionId().value(), choice);
            }
        }
        List<NpcSurfaceSnapshot.Choice> choices = new ArrayList<>(choicesByAction.values());
        int page = boundedPage(requestedPage, choices.size(), CHOICE_PAGE_SIZE);
        List<NpcSurfaceSnapshot.Choice> visible = page(choices, page, CHOICE_PAGE_SIZE);
        for (int slot = 0; slot < visible.size(); slot++) {
            NpcSurfaceSnapshot.Choice choice = visible.get(slot);
            NpcSurfaceAction action = action(surface, choice.actionId());
            boolean usable = choice.enabled() && action.enabled();
            // Item-rendered glyphs are occluded by row buttons on this build,
            // so the bullet glyph is part of the label; disabled choices get an
            // explicit ✕ because CustomNPCs does not dim disabled buttons.
            Object button = invoke(
                    gui, "addButton", 1_000 + slot, choiceLabel(usable, choice.label()),
                    GuiTheme.MARGIN, CHOICE_SLOT_Y[slot], GuiTheme.CHOICE_W - 5, 22);
            invoke(button, "setEnabled", usable);
            if (usable) {
                setButtonHandler(button, gui, binding, surface, action, page);
            }
        }
        if (choices.size() > CHOICE_PAGE_SIZE) {
            addChoicePager(gui, playerApi, player, binding, surface, page, choices.size());
        }
        if (!surface.quests().isEmpty()) {
            Object journalTitle = invoke(gui, "addLabel", 700, "Quest journal",
                    GuiTheme.QUEST_X, GuiTheme.COLUMN_TOP, 190, 14);
            setLabelColor(journalTitle, GuiTheme.COLOR_PAPER_BRIGHT);
            int questY = GuiTheme.COLUMN_TOP + 16;
            int questGlyphId = QUEST_GLYPH_BASE;
            for (NpcSurfaceSnapshot.QuestEntry quest : surface.quests()) {
                if (questY >= GuiTheme.COLUMN_BOTTOM) break;
                String iconKey = GuiTheme.questIconKey(quest.state());
                if (iconKey != null && questY + 16 <= GuiTheme.COLUMN_BOTTOM) {
                    addItemIcon(gui, questGlyphId++, GuiTheme.QUEST_X, questY, iconKey);
                }
                int color = GuiTheme.questLabelColor(quest.state());
                for (String line : wrapText(quest.title() + " — " + quest.state(), QUEST_LABEL_CHARS)) {
                    Object line2 = invoke(gui, "addLabel", 701 + questY, line,
                            GuiTheme.QUEST_TEXT_X, questY + 2, GuiTheme.QUEST_TEXT_W, 14);
                    setLabelColor(line2, color);
                    questY += 14;
                    if (questY > GuiTheme.COLUMN_BOTTOM) break;
                }
                questY += 6;
            }
        }
        Object close = invoke(gui, "addButton", 9_500, "Close",
                GuiTheme.MARGIN, GuiTheme.FOOTER_Y, 190, GuiTheme.FOOTER_H);
        setCloseHandler(close);
        invoke(playerApi, "showCustomGui", gui);
    }

    /** Choice pager: the footer's right half is free on role surfaces. */
    private void addChoicePager(
            Object gui,
            Object playerApi,
            ServerPlayer player,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            int page,
            int totalChoices) {
        int last = Math.max(0, (totalChoices - 1) / CHOICE_PAGE_SIZE);
        Object prev = invoke(gui, "addButton", PAGER_PREV_ID, "< Prev",
                218, GuiTheme.FOOTER_Y, 60, GuiTheme.FOOTER_H);
        invoke(gui, "addLabel", PAGER_LABEL_ID, (page + 1) + "/" + (last + 1),
                288, GuiTheme.FOOTER_Y + 5, 46, 14);
        Object next = invoke(gui, "addButton", PAGER_NEXT_ID, "Next >",
                340, GuiTheme.FOOTER_Y, 68, GuiTheme.FOOTER_H);
        invoke(prev, "setEnabled", page > 0);
        invoke(next, "setEnabled", page < last);
        setGuiCallback(prev, args -> reopenSurface(
                args, gui, playerApi, player, binding, surface, page - 1));
        setGuiCallback(next, args -> reopenSurface(
                args, gui, playerApi, player, binding, surface, page + 1));
    }

    private void reopenSurface(
            Object[] args,
            Object parentGui,
            Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            int page) {
        Object clickedGui = callbackGui(args, parentGui);
        Object resolvedApi = guiPlayer(clickedGui, fallbackPlayerApi);
        ServerPlayer resolvedPlayer = serverPlayer(resolvedApi, fallbackPlayer);
        if (resolvedApi != null && resolvedPlayer != null) {
            openSurface(resolvedApi, resolvedPlayer, binding, surface, page);
        }
    }



    /** Admin-only provisioning surface: select or replace the profile bound to this host NPC. */
    private void openAdminSelector(Object playerApi, ServerPlayer player, String hostUuid) {
        openAdminSelector(playerApi, player, hostUuid, 0);
    }

    private void openAdminSelector(Object playerApi, ServerPlayer player, String hostUuid, int requestedPage) {
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod(("provision:" + hostUuid).hashCode(), 20_000) + 20_000,
                GUI_WIDTH,
                ADMIN_GUI_HEIGHT,
                false,
                playerApi);
        addGuiHeader(gui, 1, "Assign profile — NPC " + shortHostId(hostUuid),
                GuiTheme.ICON_SELECTOR, GuiTheme.COLOR_PAPER_BRIGHT);
        addEntityPortrait(gui, player, hostUuid);

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
        invoke(gui, "addLabel", 2, ellipsize(currentText, WIDE_LABEL_CHARS),
                GuiTheme.MARGIN, GuiTheme.BODY_Y, GuiTheme.CONTENT_WIDTH, 20);
        if (duplicateAssignments) {
            invoke(gui, "addLabel", 3,
                    "Multiple durable bindings found. Resolve one exact binding before editing the profile.",
                    GuiTheme.MARGIN, 48, GuiTheme.CONTENT_WIDTH, 14);
        }

        // Profile rows are paged GUI-level children, not a scroll panel:
        // paged rows keep fixed positions for agents/tests, and item-rendered
        // row icons plus hover text only draw on direct GUI children.
        int listTop = duplicateAssignments ? 76 : 52;
        var options = provisioning.profiles(providerId());
        int pageSize = duplicateAssignments ? FALLBACK_PROFILE_PAGE_SIZE - 1 : FALLBACK_PROFILE_PAGE_SIZE;
        int page = boundedPage(requestedPage, options.size(), pageSize);
        var visibleOptions = page(options, page, pageSize);
        int buttonId = 2_000;
        int glyphId = PROFILE_GLYPH_BASE;
        int y = listTop;
        for (NpcProvisioningUseCase.ProfileOption option : visibleOptions) {
            boolean selected = current.map(assignment -> assignment.profileId().equals(option.profileId()))
                    .orElse(false);
            String label = profileOptionLabel(option, selected, recoveryPending);
            if (duplicateAssignments) {
                // Rows disabled by the duplicate guard get the same non-color
                // cue as disabled role choices (doc §4).
                label = "✕ " + label;
            }
            Object button = invoke(
                    gui,
                    "addButton",
                    buttonId++,
                    ellipsize(label, 58),
                    GuiTheme.MARGIN,
                    y,
                    GuiTheme.CONTENT_WIDTH,
                    22);
            // No hover text: CustomNPCs renders it anchored at the
            // component's own origin (not the cursor), which overlaps the
            // row it describes.
            invoke(button, "setEnabled", option.enabled() && !recoveryPending && !duplicateAssignments);
            if (option.enabled() && !recoveryPending && !duplicateAssignments) {
                setAdminProfileHandler(button, gui, playerApi, player, hostUuid, option, displayedRevision);
            }
            String rowIconKey = GuiTheme.roleIconKey(option.profileId());
            addItemIcon(gui, glyphId++, GuiTheme.MARGIN + 4, y + 3,
                    rowIconKey == null ? GuiTheme.ICON_CONFIRM : rowIconKey);
            y += 25;
        }
        if (options.size() > pageSize) {
            Object previous = invoke(gui, "addButton", 9_010, "Previous", 12, 185, 120, 22);
            Object next = invoke(gui, "addButton", 9_011, "Next", 276, 185, 132, 22);
            invoke(gui, "addLabel", 9_012, "Page " + (page + 1) + " / "
                    + (1 + (options.size() - 1) / pageSize), 138, 185, 132, 22);
            invoke(previous, "setEnabled", page > 0);
            invoke(next, "setEnabled", (page + 1) * pageSize < options.size());
            setAdminSelectorPageHandler(previous, gui, playerApi, player, hostUuid, page - 1);
            setAdminSelectorPageHandler(next, gui, playerApi, player, hostUuid, page + 1);
        }

        if (current.isPresent()) {
            // Keep these actions below the profile panel and inside the 240px GUI.
            Object status = invoke(gui, "addButton", 9_000, "Status",
                    GuiTheme.MARGIN, GuiTheme.FOOTER_Y, 120, GuiTheme.FOOTER_H);
            setAdminStatusHandler(status, gui, playerApi, player, hostUuid);
            addItemIcon(gui, FOOTER_GLYPH_BASE, 15, GuiTheme.FOOTER_Y + 3, GuiTheme.ICON_STATUS);
            if (!recoveryPending && !duplicateAssignments) {
                Object reproject = invoke(gui, "addButton", 9_001, "Re-sync profile",
                        138, GuiTheme.FOOTER_Y, 132, GuiTheme.FOOTER_H);
                setAdminReprojectHandler(reproject, gui, playerApi, player, hostUuid);
                addItemIcon(gui, FOOTER_GLYPH_BASE + 1, 141, GuiTheme.FOOTER_Y + 3, GuiTheme.ICON_CONFIRM);
            }
            String pendingOperation = assignmentStatus
                    .map(NpcProvisioningUseCase.AssignmentStatus::pendingOperation).orElse("");
            String unassignLabel = duplicateAssignments
                    ? "Resolve duplicates" : unassignButtonLabel(recoveryPending, pendingOperation);
            Object unassign = invoke(gui, "addButton", 9_002, unassignLabel,
                    276, GuiTheme.FOOTER_Y, 132, GuiTheme.FOOTER_H);
            addItemIcon(gui, FOOTER_GLYPH_BASE + 2, 279, GuiTheme.FOOTER_Y + 3, GuiTheme.ICON_UNASSIGN);
            if (duplicateAssignments) {
                setAdminDuplicateCleanupHandler(unassign, gui, playerApi, player, hostUuid, displayedRevision);
            } else {
                setAdminUnassignHandler(unassign, gui, playerApi, player, hostUuid, displayedRevision);
            }
        }
        invoke(playerApi, "showCustomGui", gui);
    }

    private void setAdminSelectorPageHandler(Object button, Object gui, Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer, String hostUuid, int page) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, gui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
                return;
            }
            openAdminSelector(playerApi, player, hostUuid, page);
        });
    }

    private static int boundedPage(int requested, int itemCount, int pageSize) {
        return Math.max(0, Math.min(requested, Math.max(0, itemCount - 1) / pageSize));
    }

    /**
     * Choice-button label: enabled rows get a ◆ bullet, disabled rows an
     * explicit ✕ so state isn't color-only (this build does not dim disabled
     * buttons). Labels are ellipsized to the narrow column.
     */
    static String choiceLabel(boolean usable, String label) {
        String safe = label == null ? "" : label;
        return (usable ? "◆ " : "✕ ") + ellipsize(safe, NARROW_LABEL_CHARS - 2);
    }

    static <T> java.util.List<T> page(java.util.List<T> items, int requested, int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("pageSize");
        int index = boundedPage(requested, items.size(), pageSize) * pageSize;
        return items.subList(index, Math.min(items.size(), index + pageSize));
    }

    /**
     * CustomNPCs labels draw on a single line and overflow their width; split
     * on word boundaries so each emitted line stays inside its column.
     */
    static java.util.List<String> wrapText(String text, int maxChars) {
        if (maxChars < 1) throw new IllegalArgumentException("maxChars");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String remaining = raw.strip();
            while (remaining.length() > maxChars) {
                int cut = remaining.lastIndexOf(' ', maxChars);
                if (cut <= 0) cut = maxChars;
                lines.add(remaining.substring(0, cut));
                remaining = remaining.substring(cut).stripLeading();
            }
            lines.add(remaining);
        }
        return lines;
    }

    private void openAdminDuplicateCleanup(
            Object playerApi,
            ServerPlayer player,
            String hostUuid,
            String expectedRevision) {
        openAdminDuplicateCleanup(playerApi, player, hostUuid, expectedRevision, 0);
    }

    private void openAdminDuplicateCleanup(
            Object playerApi,
            ServerPlayer player,
            String hostUuid,
            String expectedRevision,
            int requestedPage) {
        var assignments = provisioning.assignments(providerId(), hostUuid);
        Object gui = invoke(
                bridge.api(), "createCustomGui",
                Math.floorMod(("duplicates:" + hostUuid).hashCode(), 20_000) + 120_000,
                GUI_WIDTH,
                ADMIN_GUI_HEIGHT,
                false,
                playerApi);
        addGuiHeader(gui, 1, "Duplicate bindings — NPC " + shortHostId(hostUuid),
                GuiTheme.ICON_CLEANUP, GuiTheme.COLOR_PAPER_BRIGHT);
        int descriptionY = GuiTheme.BODY_Y;
        for (String line : wrapText(
                "Two bindings claim this NPC. Release one; the other remains.",
                WIDE_LABEL_CHARS)) {
            invoke(gui, "addLabel", 2 + descriptionY, line,
                    GuiTheme.MARGIN, descriptionY, GuiTheme.CONTENT_WIDTH, 14);
            descriptionY += 14;
        }
        // Paged GUI-level rows (same reasoning as the selector: icons and
        // hover text only render on direct GUI children).
        int pageSize = FALLBACK_PROFILE_PAGE_SIZE - 1;
        int page = boundedPage(requestedPage, assignments.size(), pageSize);
        var visibleAssignments = page(assignments, page, pageSize);
        int buttonId = 200;
        int glyphId = ROW_GLYPH_BASE;
        int y = 80;
        for (NpcProvisioningUseCase.AssignmentView assignment : visibleAssignments) {
            Object button = invoke(gui, "addButton", buttonId++,
                    ellipsize("Release: " + assignment.profileId()
                            + " — " + (assignment.assignedBy().isBlank() ? "unknown" : assignment.assignedBy())
                            + ", " + formatDay(assignment.assignedAtEpochMillis()), 58),
                    GuiTheme.MARGIN, y, GuiTheme.CONTENT_WIDTH, 22);
            setAdminDuplicateBindingHandler(button, gui, playerApi, player, hostUuid,
                    assignment.bindingId(), expectedRevision);
            String rowIconKey = GuiTheme.roleIconKey(assignment.profileId());
            addItemIcon(gui, glyphId++, GuiTheme.MARGIN + 4, y + 3,
                    rowIconKey == null ? GuiTheme.ICON_CLEANUP : rowIconKey);
            y += 25;
        }
        if (assignments.size() > pageSize) {
            Object previous = invoke(gui, "addButton", 12_901, "Previous", 12, 185, 120, 22);
            Object next = invoke(gui, "addButton", 12_902, "Next", 276, 185, 132, 22);
            invoke(gui, "addLabel", 12_903, "Page " + (page + 1) + " / "
                    + (1 + (assignments.size() - 1) / pageSize), 138, 185, 132, 22);
            invoke(previous, "setEnabled", page > 0);
            invoke(next, "setEnabled", (page + 1) * pageSize < assignments.size());
            setAdminDuplicatePageHandler(previous, gui, playerApi, player, hostUuid, expectedRevision, page - 1);
            setAdminDuplicatePageHandler(next, gui, playerApi, player, hostUuid, expectedRevision, page + 1);
        }
        Object back = invoke(gui, "addButton", 12_900, "Back", 218, 214, 190, 22);
        setAdminCancelHandler(back, gui, playerApi, player, hostUuid);
        invoke(playerApi, "showCustomGui", gui);
    }

    private void setAdminDuplicatePageHandler(Object button, Object gui, Object fallbackPlayerApi,
            ServerPlayer fallbackPlayer, String hostUuid, String expectedRevision, int page) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, gui);
            Object playerApi = guiPlayer(clickedGui, fallbackPlayerApi);
            ServerPlayer player = serverPlayer(playerApi, fallbackPlayer);
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
                return;
            }
            openAdminDuplicateCleanup(playerApi, player, hostUuid, expectedRevision, page);
        });
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
                GUI_WIDTH,
                ADMIN_GUI_HEIGHT,
                false,
                playerApi);
        addGuiHeader(gui, 1, "Confirm profile — NPC " + shortHostId(hostUuid),
                GuiTheme.ICON_CONFIRM, GuiTheme.COLOR_PAPER_BRIGHT);
        invoke(gui, "addLabel", 2,
                "Profile: " + ellipsize(option.profileId(), WIDE_LABEL_CHARS)
                        + " · " + ellipsize(option.title(), 24) + " · " + option.roleId(),
                GuiTheme.MARGIN, GuiTheme.BODY_Y, GuiTheme.CONTENT_WIDTH, 14);
        String previousProfile = provisioning.current(providerId(), hostUuid)
                .map(NpcProvisioningUseCase.AssignmentView::profileId).orElse("unassigned");
        invoke(gui, "addLabel", 4, "Replacing: " + ellipsize(previousProfile, WIDE_LABEL_CHARS - 11),
                GuiTheme.MARGIN, 48, GuiTheme.CONTENT_WIDTH, 14);
        // Reference rows go through a disabled text area: labels placed
        // inside this scrolling panel render blank in CustomNPCs builds.
        java.util.List<String> referenceRows = new ArrayList<>(profileReferenceRows(option));
        // Hover text is unusable on this build, so the info lines that used to
        // be a row tooltip render inline on the confirmation instead.
        referenceRows.add("");
        referenceRows.addAll(List.of(profileOptionInfoLines(option, false)));
        Object summary = invoke(gui, "addTextArea", 3, GuiTheme.MARGIN, 76,
                GuiTheme.CONTENT_WIDTH, 60);
        invoke(summary, "setText", String.join("\n", referenceRows));
        invoke(summary, "setEnabled", false);
        Object confirm = invoke(gui, "addButton", 9_100, "Assign profile", 12, 150, 190, 22);
        addItemIcon(gui, INLINE_ICON_ID + 1, 15, 153, GuiTheme.ICON_CONFIRM);
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

    static String[] profileOptionInfoLines(
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
                GUI_WIDTH,
                ADMIN_GUI_HEIGHT,
                false,
                playerApi);
        addGuiHeader(gui, 1,
                (recoveryPending ? "Cancel pending — NPC " : "Unassign profile — NPC ")
                        + shortHostId(hostUuid),
                GuiTheme.ICON_UNASSIGN, GuiTheme.COLOR_PAPER_BRIGHT);
        String profileId = provisioning.assignments(providerId(), hostUuid).stream()
                .filter(assignment -> assignment.bindingId().equals(bindingId))
                .map(NpcProvisioningUseCase.AssignmentView::profileId)
                .findFirst().orElse("no longer assigned");
        invoke(gui, "addLabel", 2, "Profile: " + ellipsize(profileId, WIDE_LABEL_CHARS - 9),
                GuiTheme.MARGIN, GuiTheme.BODY_Y, GuiTheme.CONTENT_WIDTH, 14);
        String unassignNote = recoveryPending
                ? "Straja will remove this assignment only after the provider confirms it is unbound."
                : provisioning.assignments(providerId(), hostUuid).size() > 1
                        ? "This releases one binding; other Straja bindings on this NPC stay active."
                        : "This NPC will return to native CustomNPCs behavior.";
        int noteY = 48;
        for (String line : wrapText(unassignNote, WIDE_LABEL_CHARS)) {
            invoke(gui, "addLabel", 3 + noteY, line, GuiTheme.MARGIN, noteY,
                    GuiTheme.CONTENT_WIDTH, 14);
            noteY += 14;
        }
        Object confirm = invoke(gui, "addButton", 9_200,
                recoveryPending ? "Confirm cancellation" : "Unassign", 12, 150, 190, 22);
        addItemIcon(gui, INLINE_ICON_ID + 1, 15, 153, GuiTheme.ICON_UNASSIGN);
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
                GUI_WIDTH, ADMIN_GUI_HEIGHT, false, playerApi);
        addGuiHeader(gui, 1, "NPC assignment status",
                GuiTheme.ICON_STATUS, GuiTheme.COLOR_PAPER_BRIGHT);
        Object details = invoke(gui, "addTextArea", 2, 12, 34, 396, 172);
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
        Object audit = invoke(gui, "addButton", 9_100, "Audit history",
                GuiTheme.MARGIN, GuiTheme.FOOTER_Y, 190, GuiTheme.FOOTER_H);
        addItemIcon(gui, INLINE_ICON_ID, 15, GuiTheme.FOOTER_Y + 3, GuiTheme.ICON_AUDIT);
        Object back = invoke(gui, "addButton", 9_101, "Back",
                218, GuiTheme.FOOTER_Y, 190, GuiTheme.FOOTER_H);
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
                GUI_WIDTH, ADMIN_GUI_HEIGHT, false, playerApi);
        addGuiHeader(gui, 1, "Provisioning audit",
                GuiTheme.ICON_AUDIT, GuiTheme.COLOR_PAPER_BRIGHT);
        Object details = invoke(gui, "addTextArea", 2, 12, 34, 396, 172);
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
        Object back = invoke(gui, "addButton", 9_102, "Back to status",
                GuiTheme.MARGIN, GuiTheme.FOOTER_Y, 190, GuiTheme.FOOTER_H);
        setGuiCallback(back, args -> {
            Object clickedGui = callbackGui(args, gui);
            Object currentPlayerApi = guiPlayer(clickedGui, playerApi);
            ServerPlayer currentPlayer = serverPlayer(currentPlayerApi, player);
            if (!isAdminCallbackAuthorized(clickedGui, currentPlayerApi, currentPlayer)) {
                showAuthorizationResult(clickedGui, currentPlayer, hostUuid);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
                return;
            }
            NpcProviderResult host = inspectHost(player.getServer(), hostUuid);
            if (host.status() != NpcProviderResult.Status.ACCEPTED) {
                showProvisioningResult(clickedGui, player, hostUuid, provisioningResult(host));
                return;
            }
            NpcProvisioningUseCase.ProvisioningResult result = provisioning.reproject(
                    providerId(), hostUuid, player.getUUID().toString());
            if (result.status() == NpcProvisioningUseCase.Status.ACCEPTED) {
                openAdminSelector(playerApi, player, hostUuid);
            } else {
                showProvisioningResult(clickedGui, player, hostUuid, result);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
                return;
            }
            NpcProviderResult host = inspectHost(player.getServer(), hostUuid);
            if (host.status() != NpcProviderResult.Status.ACCEPTED) {
                showProvisioningResult(clickedGui, player, hostUuid, provisioningResult(host));
                return;
            }
            NpcProvisioningUseCase.ProvisioningResult result = provisioning.assignIfRevisionMatches(
                    providerId(), hostUuid, player.getUUID().toString(), option.profileId(),
                    hostLocation(player.getServer(), hostUuid), expectedRevision);
            if (result.status() == NpcProvisioningUseCase.Status.ACCEPTED) {
                openAdminSelector(playerApi, player, hostUuid);
            } else {
                showProvisioningResult(clickedGui, player, hostUuid, result);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
                return;
            }
            NpcProvisioningUseCase.ProvisioningResult result = provisioning.unassignBindingIfRevisionMatches(
                    providerId(), hostUuid, bindingId, player.getUUID().toString(), expectedRevision);
            if (result.status() == NpcProvisioningUseCase.Status.ACCEPTED) {
                openAdminSelector(playerApi, player, hostUuid);
            } else {
                showProvisioningResult(clickedGui, player, hostUuid, result);
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
            if (!isAdminCallbackAuthorized(clickedGui, playerApi, player)) {
                showAuthorizationResult(clickedGui, player, hostUuid);
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
            return raw instanceof ServerPlayer player && player == fallback ? player : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isAdminCallbackAuthorized(Object gui, Object playerApi, ServerPlayer player) {
        return player != null
                && player.getServer() != null
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && adminTool != null
                && adminTool.test(player)
                && isCurrentAdminGui(playerApi, gui);
    }

    static boolean isCurrentAdminGui(Object playerApi, Object gui) {
        if (playerApi == null || gui == null) return false;
        try {
            return invoke(playerApi, "getCustomGui") == gui;
        } catch (RuntimeException ignored) {
            return false;
        }
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

    /**
     * Header band per docs/npc-surface-visual-system.md: 16px item icon,
     * parchment-bright title, leather rule. Icon and rule degrade to absent
     * when the CustomNPCs component or the item is unavailable — a header
     * never blocks the surface.
     */
    private void addGuiHeader(Object gui, int titleId, String title, String iconKey, int titleColor) {
        applyPanelBackground(gui);
        addHeaderIcon(gui, iconKey);
        Object label = invoke(gui, "addLabel", titleId, title,
                GuiTheme.TITLE_X, GuiTheme.HEADER_Y, GuiTheme.TITLE_WIDTH, 20);
        try {
            invoke(label, "setColor", titleColor);
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs label color unavailable; header renders default");
        }
        try {
            invoke(gui, "addColoredLine", HEADER_RULE_ID,
                    GuiTheme.MARGIN, GuiTheme.RULE_Y,
                    GuiTheme.MARGIN + GuiTheme.CONTENT_WIDTH, GuiTheme.RULE_Y,
                    GuiTheme.COLOR_LEATHER, 1.0f);
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs header rule unavailable; header renders without it");
        }
    }

    /**
     * Header icon = item renderer (guaranteed base) + generated PNG overlay.
     * CustomNPCs 1.21.1-unofficial accepts textured rect/button components
     * server-side but does not draw their textures client-side (verified
     * against the shipped jar and live pixels); the item renderer still shows
     * the role/action icon. On builds where textured components render, the
     * PNG is drawn on top of the item at the same position.
     */
    private void addHeaderIcon(Object gui, String iconKey) {
        if (iconKey == null) {
            return;
        }
        addItemIcon(gui, HEADER_ICON_ID, GuiTheme.MARGIN, GuiTheme.HEADER_Y, iconKey);
        if (GuiTheme.USE_TEXTURE_ICONS && GuiTheme.hasTextureIcon(iconKey)) {
            addTexturedIcon(gui, iconKey);
        }
    }

    /**
     * Guaranteed-visible icon layer: a 16x16 Straja item rendered through
     * CustomNPCs' item-renderer component. Works at GUI level and inside
     * scroll panels; silently absent when the item/component is unsupported.
     */
    private void addItemIcon(Object host, int id, int x, int y, String iconKey) {
        String iconItemId = GuiTheme.iconItemFallback(iconKey);
        if (iconItemId == null) {
            return;
        }
        try {
            // GuiItemIcons owns the Minecraft item types so this class stays
            // verifiable in the Minecraft-free unit-test JVM.
            Object stack = GuiItemIcons.mcItemStack(iconItemId);
            if (stack == null) {
                return;
            }
            Object wrapped = invoke(bridge.api(), "getIItemStack", stack);
            invoke(host, "addItemRenderer", id, x, y,
                    GuiTheme.HEADER_ICON_SIZE, GuiTheme.HEADER_ICON_SIZE, wrapped);
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs item icon unavailable for " + iconKey);
        }
    }

    /**
     * Selector portrait: live render of the target NPC via IEntityDisplay.
     * Placement is in the header-right corner, above the rule; the component
     * is decorative and degrades to absent when unsupported.
     */
    private void addEntityPortrait(Object gui, ServerPlayer player, String hostUuid) {
        try {
            Entity entity = player.serverLevel().getEntity(UUID.fromString(hostUuid));
            if (entity == null) {
                return;
            }
            Object wrapped = invoke(bridge.api(), "getIEntity", entity);
            if (wrapped == null) {
                return;
            }
            Object display = invoke(gui, "addEntityDisplay", PORTRAIT_ID, 388, 4, wrapped);
            // addEntityDisplay leaves the component at 0x0 — size is required
            // or the render loop clips it to a point.
            invoke(display, "setSize", 24, 26);
            invoke(display, "setBackground", false);
            invoke(display, "setScale", 1.0f);
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs entity portrait unavailable; selector renders without it");
        }
    }

    private void setLabelColor(Object label, int color) {
        try {
            invoke(label, "setColor", color);
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs label color unavailable; label renders default");
        }
    }

    /** Close buttons call {@code ICustomGui.close()} on the live GUI. */
    private void setCloseHandler(Object button) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, null);
            if (clickedGui != null) {
                invoke(clickedGui, "close");
            }
        });
    }

    /** Middle-truncate long ids/messages so single-line labels stay inside their column. */
    static String ellipsize(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        if (max < 8) {
            return text.substring(0, max);
        }
        int head = (max - 1) / 2;
        int tail = max - 1 - head;
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    /** UUIDs in titles shorten to the first segment (mockup: "NPC 8f3a…"). */
    static String shortHostId(String hostUuid) {
        return hostUuid == null || hostUuid.length() <= 12 ? hostUuid : hostUuid.substring(0, 8) + "…";
    }

    private static String formatDay(long epochMillis) {
        if (epochMillis <= 0) {
            return "unknown";
        }
        return DateTimeFormatter.ofPattern("MM-dd")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(epochMillis));
    }

    /** Tier-2 PNG overlay for builds where textured components draw. */
    private void addTexturedIcon(Object gui, String iconKey) {
        try {
            // 8-arg form: explicit texture offset — the 6-arg wrapper leaves
            // textureX/Y at -1 which skips texPos serialization client-side.
            invoke(gui, "addTexturedRect", HEADER_TEXTURE_ICON_ID,
                    GuiTheme.iconTexture(iconKey),
                    GuiTheme.MARGIN, GuiTheme.HEADER_Y,
                    GuiTheme.HEADER_ICON_SIZE, GuiTheme.HEADER_ICON_SIZE, 0, 0);
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs textured icon unavailable for " + iconKey);
        }
    }

    /** Generated parchment panel; absent texture support degrades silently. */
    private void applyPanelBackground(Object gui) {
        if (!GuiTheme.USE_PANEL_BACKGROUND) {
            return;
        }
        try {
            invoke(gui, "setBackgroundTexture", GuiTheme.PANEL_TEXTURE);
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs panel background unavailable; keeping default");
        }
    }

    private void showAuthorizationResult(Object gui, ServerPlayer player, String hostUuid) {
        if (player == null || player.getServer() == null
                || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
        try {
            if (!isCurrentAdminGui(playerApi(player), gui)) return;
        } catch (RuntimeException ignored) {
            return;
        }
        showProvisioningResult(gui, player, hostUuid, NpcProvisioningUseCase.ProvisioningResult.rejected(
                "admin-authorization-required",
                "NPC Tool permission and held wand are required for this action."));
    }

    private void showProvisioningResult(
            Object gui,
            ServerPlayer player,
            String hostUuid,
            NpcProvisioningUseCase.ProvisioningResult result) {
        if (player == null) return;
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
        // Inline labels draw underneath the scrolling panels on these admin
        // GUIs, so outcomes get their own terminal screen instead.
        Object guiPlayerApi = guiPlayer(gui, null);
        if (guiPlayerApi == null) {
            try {
                guiPlayerApi = playerApi(player);
            } catch (RuntimeException exception) {
                diagnostics.accept("Could not reopen NPC provisioning result GUI: "
                        + exception.getClass().getSimpleName());
                return;
            }
        }
        if (guiPlayerApi == null) return;
        openAdminResult(guiPlayerApi, player, hostUuid, result);
    }

    /** Terminal admin screen: outcome message plus a way back to the selector. */
    private void openAdminResult(
            Object playerApi,
            ServerPlayer player,
            String hostUuid,
            NpcProvisioningUseCase.ProvisioningResult result) {
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod(("result:" + hostUuid + result.message()).hashCode(), 20_000) + 140_000,
                GUI_WIDTH,
                ADMIN_GUI_HEIGHT,
                false,
                playerApi);
        boolean accepted = result.status() == NpcProvisioningUseCase.Status.ACCEPTED;
        String severityIcon = accepted ? GuiTheme.ICON_OK : GuiTheme.ICON_DENIED;
        addGuiHeader(gui, 1, "NPC provisioning — " + result.status(),
                severityIcon,
                accepted ? GuiTheme.COLOR_BRASS : GuiTheme.COLOR_SEAL_BRIGHT);
        addItemIcon(gui, INLINE_ICON_ID, GuiTheme.MARGIN, 40, severityIcon);
        int y = 42;
        int rowId = 200;
        for (String line : wrapText(result.message(), RESULT_LABEL_CHARS)) {
            invoke(gui, "addLabel", rowId++, line, 36, y, 372, 14);
            y += 14;
        }
        Object back = invoke(gui, "addButton", 9_300, "Back",
                GuiTheme.MARGIN, GuiTheme.FOOTER_Y, 190, GuiTheme.FOOTER_H);
        setAdminCancelHandler(back, gui, playerApi, player, hostUuid);
        invoke(playerApi, "showCustomGui", gui);
    }

    private void setButtonHandler(
            Object button,
            Object gui,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcSurfaceAction action,
            int page) {
        try {
            Class<?> callbackType = Class.forName(
                    "noppes.npcs.api.function.gui.GuiComponentClicked",
                    true,
                    button.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onClick") && args != null && args.length == 2) {
                    if (action.inputs().isEmpty()) {
                        submitAction(args[0], 2, binding, surface, action.actionId(), Map.of(), page);
                    } else {
                        openInputGui(args[0], binding, surface, action, page);
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
            NpcSurfaceAction action,
            int page) {
        Object playerApi = invoke(parentGui, "getPlayer");
        // Keep the provider-specific factory order explicit at this boundary.
        // 421x240 standard class; the prompt band restates the question(s)
        // above the fields (docs/npc-surface-visual-system.md, input mockup).
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                Math.floorMod((binding.bindingId() + action.actionId().value()).hashCode(), 20_000) + 1_000,
                GUI_WIDTH,
                GuiTheme.GUI_HEIGHT,
                false,
                playerApi);
        addGuiHeader(gui, 1, ellipsize(action.label(), TITLE_CHARS),
                GuiTheme.ICON_INPUT, GuiTheme.COLOR_PAPER_BRIGHT);

        // Id 8 doubles as the result sink: rejected submissions rewrite the
        // prompt band with the outcome message (see showResult).
        var visibleFields = action.inputs().stream().filter(NpcSurfaceAction.InputField::visible).toList();
        String prompt = visibleFields.size() == 1
                ? visibleFields.get(0).label()
                : visibleFields.isEmpty()
                        ? "Submit this action."
                        : "Complete all fields below.";
        Object promptArea = invoke(gui, "addTextArea", 8, GuiTheme.MARGIN, GuiTheme.BODY_Y,
                GuiTheme.CONTENT_WIDTH, 40);
        invoke(promptArea, "setText", prompt);
        invoke(promptArea, "setEnabled", false);

        Map<String, Integer> fieldIds = new java.util.LinkedHashMap<>();
        // Fields are GUI-level children, not scroll-panel content: this
        // CustomNPCs build never delivers clicks to scrolled panel children,
        // so any field below the fold would be unreachable (the arrest-handoff
        // form has three visible fields).
        // Ids start at 20 so a full 16-field form can't collide with the
        // prompt-area id 8 (which doubles as the showResult sink).
        int nextId = 20;
        int y = 72;
        boolean multiField = visibleFields.size() > 1;
        for (NpcSurfaceAction.InputField field : action.inputs()) {
            // Single-field surfaces restate the label in the prompt band; a
            // multi-field form keeps each label directly above its field.
            if (field.visible() && multiField) {
                invoke(gui, "addLabel", 10_000 + nextId, field.label(), GuiTheme.MARGIN, y,
                        GuiTheme.CONTENT_WIDTH, 12);
                y += 12;
            } else if (field.visible()) {
                y += 8;
            }
            Object textField = invoke(gui, "addTextField", nextId, GuiTheme.MARGIN, y,
                    GuiTheme.CONTENT_WIDTH, 20);
            if (!field.initialValue().isEmpty()) {
                invoke(textField, "setText", field.initialValue());
            }
            if (!field.visible()) {
                invoke(textField, "setVisible", false);
            }
            fieldIds.put(field.key(), nextId++);
            y += field.visible() ? (multiField ? 34 : 28) : 2;
        }
        Object fieldHost = gui;
        Object submit = invoke(gui, "addButton", 9_500, "Submit",
                GuiTheme.MARGIN, GuiTheme.FOOTER_Y, 190, GuiTheme.FOOTER_H);
        addItemIcon(gui, INLINE_ICON_ID + 2, 15, GuiTheme.FOOTER_Y + 3, GuiTheme.ICON_INPUT);
        setInputButtonHandler(submit, fieldHost, binding, surface, action, fieldIds, page);
        Object cancel = invoke(gui, "addButton", 9_501, "Cancel",
                218, GuiTheme.FOOTER_Y, 190, GuiTheme.FOOTER_H);
        setInputCancelHandler(cancel, binding, page);
        invoke(playerApi, "showCustomGui", gui);
    }

    /** Cancel returns to the role surface when it is still published, else closes. */
    private void setInputCancelHandler(Object button, NpcBinding binding, int page) {
        setGuiCallback(button, args -> {
            Object clickedGui = callbackGui(args, null);
            Object playerApi = guiPlayer(clickedGui, null);
            try {
                NpcSurfaceSnapshot published = surfaces.get(binding.bindingId());
                if (playerApi != null && published != null) {
                    Object rawPlayer = invoke(playerApi, "getMCEntity");
                    if (rawPlayer instanceof ServerPlayer player) {
                        NpcSurfaceSnapshot resolved =
                                surfaceResolver.resolve(player.getUUID(), binding, published);
                        if (resolved != null) {
                            openSurface(playerApi, player, binding, resolved, page);
                            return;
                        }
                    }
                }
            } catch (RuntimeException exception) {
                diagnostics.accept("CustomNPCs input cancel could not reopen the surface");
            }
            if (clickedGui != null) {
                invoke(clickedGui, "close");
            }
        });
    }

    private void setInputButtonHandler(
            Object button,
            Object fieldHost,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcSurfaceAction action,
            Map<String, Integer> fieldIds,
            int page) {
        try {
            Class<?> callbackType = Class.forName(
                    "noppes.npcs.api.function.gui.GuiComponentClicked",
                    true,
                    button.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onClick") && args != null && args.length == 2) {
                    submitInputAction(args[0], fieldHost, binding, surface, action, fieldIds, page);
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
            Map<String, Integer> fieldIds,
            int page) {
        Map<String, String> input = new java.util.LinkedHashMap<>();
        for (NpcSurfaceAction.InputField field : action.inputs()) {
            Object component = invoke(fieldHost, "getComponent", fieldIds.get(field.key()));
            Object raw = invoke(component, "getText");
            String value = raw == null ? "" : String.valueOf(raw);
            if (value.length() > field.maxLength()) {
                showResult(gui, 8, new NpcActionResult(
                        NpcActionResult.Status.REJECTED,
                        "input-too-long",
                        field.label() + " exceeds the allowed length."));
                return;
            }
            if (field.required() && value.isBlank()) {
                showResult(gui, 8, new NpcActionResult(
                        NpcActionResult.Status.REJECTED,
                        "input-required",
                        field.label() + " is required."));
                return;
            }
            input.put(field.key(), value);
        }
        submitAction(gui, 8, binding, surface, action.actionId(), input, page);
    }

    private void submitAction(
            Object gui,
            int resultSinkId,
            NpcBinding binding,
            NpcSurfaceSnapshot surface,
            NpcContentId actionId,
            Map<String, String> input,
            int page) {
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
                    NpcSurfaceSnapshot resolved =
                            surfaceResolver.resolve(player.getUUID(), binding, published);
                    if (resolved != null) {
                        openSurface(playerApi, player, binding, resolved, page);
                        return;
                    }
                }
            }
            showResult(gui, resultSinkId, result);
        } catch (RuntimeException exception) {
            showResult(gui, resultSinkId, new NpcActionResult(
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

    /**
     * Action outcomes rewrite the surface's result-sink TextArea (role body
     * id 2, input prompt id 8): labels added over scroll-panel regions draw
     * underneath them on CustomNPCs 1.21.1 and were never readable.
     */
    private void showResult(Object gui, int sinkId, NpcActionResult result) {
        String text = result.status() == NpcActionResult.Status.ACCEPTED
                ? result.message() : "⚠ " + result.message();
        try {
            Object sink = invoke(gui, "getComponent", sinkId);
            if (sink != null) {
                invoke(sink, "setText", text);
                invoke(gui, "update", sink);
                return;
            }
        } catch (RuntimeException exception) {
            diagnostics.accept("CustomNPCs result sink unavailable; using label fallback");
        }
        try {
            invoke(gui, "removeComponent", 9_000);
        } catch (RuntimeException ignored) {
            // Removing a first result label is optional.
        }
        invoke(gui, "addLabel", 9_000, result.message(), GuiTheme.MARGIN, 190,
                GuiTheme.CONTENT_WIDTH, 14);
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

    static boolean compatible(Class<?>[] parameters, Object[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            if (arguments[i] == null) continue;
            Class<?> parameter = parameters[i];
            Class<?> argument = arguments[i].getClass();
            if (parameter.isPrimitive()) {
                if ((parameter == int.class && argument == Integer.class)
                        || (parameter == boolean.class && argument == Boolean.class)
                        || (parameter == float.class && argument == Float.class)
                        || (parameter == double.class && argument == Double.class)
                        || (parameter == long.class && argument == Long.class)) continue;
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
