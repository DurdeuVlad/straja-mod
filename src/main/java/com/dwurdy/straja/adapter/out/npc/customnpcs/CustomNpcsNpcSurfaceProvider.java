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
    private final ReflectionBridge bridge;
    private final Map<String, NpcBinding> bindings = new ConcurrentHashMap<>();
    private final Map<String, NpcSurfaceSnapshot> surfaces = new ConcurrentHashMap<>();

    public CustomNpcsNpcSurfaceProvider(
            NpcSurfaceActionUseCase actions,
            NpcSurfaceActionTokenIssuer tokenIssuer,
            Consumer<String> diagnostics) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
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
            NpcSurfaceSnapshot surface = surfaces.get(binding.bindingId());
            if (surface == null) {
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

        int y = 98;
        for (NpcSurfaceSnapshot.DialogueNode node : surface.dialogue()) {
            invoke(gui, "addLabel", 100 + y, node.text(), 12, y, 396, 20);
            y += 22;
            for (NpcSurfaceSnapshot.Choice choice : node.choices()) {
                NpcSurfaceAction action = action(surface, choice.actionId());
                Object button = invoke(gui, "addButton", 1_000 + y, choice.label(), 12, y, 190, 20);
                invoke(button, "setEnabled", choice.enabled() && action.enabled());
                if (choice.enabled() && action.enabled()) {
                    setButtonHandler(button, gui, binding, action.actionId());
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

    private void setButtonHandler(Object button, Object gui, NpcBinding binding, NpcContentId actionId) {
        try {
            Class<?> callbackType = Class.forName(
                    "noppes.npcs.api.function.gui.GuiComponentClicked",
                    true,
                    button.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onClick") && args != null && args.length == 2) {
                    submitAction(args[0], binding, actionId);
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

    private void submitAction(Object gui, NpcBinding binding, NpcContentId actionId) {
        try {
            Object playerApi = invoke(gui, "getPlayer");
            Object rawPlayer = invoke(playerApi, "getMCEntity");
            if (!(rawPlayer instanceof ServerPlayer player)) {
                return;
            }
            String token = tokenIssuer.issueToken(player.getUUID(), binding, actionId);
            NpcActionResult result = actions.submit(new NpcActionRequest(
                    providerId(), binding.bindingId(), player.getUUID(), actionId, token, Map.of()));
            showResult(gui, result);
        } catch (RuntimeException exception) {
            showResult(gui, new NpcActionResult(
                    NpcActionResult.Status.UNAVAILABLE,
                    "bridge-failed",
                    "This Straja action is temporarily unavailable."));
        }
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
                Method addListener = java.util.Arrays.stream(eventBus.getClass().getMethods())
                        .filter(method -> method.getName().equals("addListener")
                                && method.getParameterCount() == 1)
                        .findFirst()
                        .orElseThrow(() -> new NoSuchMethodException("addListener"));
                addListener.invoke(eventBus, listener);
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
