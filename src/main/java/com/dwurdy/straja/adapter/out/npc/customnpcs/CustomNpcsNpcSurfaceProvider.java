package com.dwurdy.straja.adapter.out.npc.customnpcs;

import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionUseCase;
import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

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
    private final ReflectionBridge bridge;
    private final Map<String, NpcBinding> bindings = new ConcurrentHashMap<>();
    private final Map<String, NpcSurfaceSnapshot> surfaces = new ConcurrentHashMap<>();

    public CustomNpcsNpcSurfaceProvider(
            NpcSurfaceActionUseCase actions,
            NpcSurfaceActionTokenIssuer tokenIssuer,
            Consumer<String> diagnostics) {
        this(actions, tokenIssuer, diagnostics, (playerId, binding, published) -> published);
    }

    public CustomNpcsNpcSurfaceProvider(
            NpcSurfaceActionUseCase actions,
            NpcSurfaceActionTokenIssuer tokenIssuer,
            Consumer<String> diagnostics,
            SurfaceResolver surfaceResolver) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.surfaceResolver = Objects.requireNonNull(surfaceResolver, "surfaceResolver");
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
    public boolean available() {
        return bridge.available();
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
        if (!event.getClass().getName().equals(
                "noppes.npcs.api.event.NpcEvent$InteractEvent")) {
            return;
        }
        try {
            Object npc = field(event, "npc");
            String hostUuid = String.valueOf(invoke(npc, "getUUID"));
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
            Object playerApi = field(event, "player");
            Object rawPlayer = invoke(playerApi, "getMCEntity");
            if (!(rawPlayer instanceof ServerPlayer player)) {
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
            diagnostics.accept("CustomNPCs interaction ignored after bridge error: "
                    + exception.getClass().getSimpleName());
        }
    }

    private void openSurface(
            Object playerApi,
            ServerPlayer player,
            NpcBinding binding,
            NpcSurfaceSnapshot surface) {
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                421,
                320,
                Math.floorMod(binding.bindingId().hashCode(), 20_000) + 1_000,
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
        Object gui = invoke(
                bridge.api(),
                "createCustomGui",
                422,
                320,
                Math.floorMod((binding.bindingId() + action.actionId().value()).hashCode(), 20_000) + 1_000,
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
                Class<?> eventType = Class.forName(
                        "noppes.npcs.api.event.NpcEvent$InteractEvent");
                registerInteractionListener(eventBus, eventType, listener);
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
