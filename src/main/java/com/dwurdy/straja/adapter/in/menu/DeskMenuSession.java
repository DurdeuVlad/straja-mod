package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.adapter.in.StrajaText;
import com.dwurdy.straja.adapter.in.npc.NpcFaqSurface;
import com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface;
import com.dwurdy.straja.adapter.in.npc.NpcRoles;
import com.dwurdy.straja.application.port.out.DeskSurfaceBridge;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One desk-menu session — lifetime equals the container's. Owns the nav stack
 * and renders {@link DeskPage}s into the menu's grid; page turns reuse the
 * same container via {@code broadcastChanges} (no reopen packets), and every
 * action click dispatches {@code NpcRoles.performAction} directly — no chat
 * tokens are minted inside an open menu.
 */
final class DeskMenuSession {
    private static final int MAX_TITLE = 60;
    private static final long CLICK_GRACE_NANOS = 150_000_000L;

    private final ServerPlayer player;
    private final String roleId;
    private final DeskNavigation nav;
    private final DeskSurfaceBridge surfaceBridge;
    private final String sessionId = UUID.randomUUID().toString();
    private DeskGuiMenu menu;
    private DeskPage page;
    private int seq;
    private boolean alive = true;
    private long clickGraceUntilNanos;

    DeskMenuSession(ServerPlayer player, String roleId) {
        this(player, roleId, new DeskNavigation(), null);
    }

    private DeskMenuSession(ServerPlayer player, String roleId,
                            DeskNavigation nav, DeskSurfaceBridge surfaceBridge) {
        this.player = player;
        this.roleId = roleId;
        this.nav = nav;
        this.surfaceBridge = surfaceBridge;
    }

    String sessionId() { return sessionId; }

    /** Monotonic page sequence — clicks carrying a stale seq are dropped. */
    int seq() { return seq; }

    /**
     * Diagnostic view of the current page — one "slot|action|name" line per
     * button so the test surface can resolve labels to wire slots.
     */
    public List<String> describeButtons() {
        DeskPage current = page;
        if (current == null) return List.of();
        var lines = new ArrayList<String>(current.buttons().size() + 1);
        lines.add("title=" + current.title());
        for (DeskButton button : current.buttons()) {
            lines.add(button.slot() + "|" + button.actionId()
                    + "|" + button.icon().name());
        }
        return lines;
    }

    /** Admin-tool grid: a flat list root dispatching straight to performAction. */
    static DeskMenuSession forList(ServerPlayer player, String title, List<DeskButton> items) {
        return new DeskMenuSession(player, null,
                new DeskNavigation(new DeskPageSource.ListPage(title, items, 0)), null);
    }

    /**
     * Provider-bound surface session: the nav root resolves the published
     * snapshot through {@code surfaceBridge} and clicks mint provider action
     * tokens — the same contract as the CustomNPCs GuiCustom path.
     */
    static DeskMenuSession forSurface(ServerPlayer player, NpcBinding binding,
                                      DeskSurfaceBridge surfaceBridge) {
        return new DeskMenuSession(player, null,
                new DeskNavigation(new DeskPageSource.SurfacePage(binding, 0)), surfaceBridge);
    }

    /**
     * Opens the chest menu once, rendering page 0 of the ordered surface.
     * Subsequent page turns reuse this container; {@link #isAlive} ends the
     * session when the menu is replaced (e.g. a form opens) or removed.
     */
    boolean open(NpcPlayerSurface.OrderedSurface ordered) {
        return openContainer(rolePage(ordered, 0));
    }

    /** Opens the menu on the session's own root page (tool lists, bound surfaces). */
    boolean open() {
        return openContainer(renderPage(nav.current()));
    }

    private boolean openContainer(DeskPage first) {
        page = first;
        String title = page.title().length() > MAX_TITLE
                ? page.title().substring(0, MAX_TITLE) : page.title();
        var opened = player.openMenu(new SimpleMenuProvider(
                (containerId, inv, p) -> {
                    menu = new DeskGuiMenu(containerId, inv, this, sessionId);
                    return menu;
                },
                Component.literal(title)),
                buf -> DeskGuiMenu.writeView(buf,
                        DeskWire.viewOf(sessionId, seq, first)));
        if (opened.isEmpty() || menu == null) {
            alive = false;
            menu = null;
            return false;
        }
        return true;
    }

    boolean isAlive() {
        return alive && menu != null && player.containerMenu == menu
                && !player.hasDisconnected();
    }

    void onRemoved() {
        alive = false;
    }

    /**
     * Routes one widget activation (the {@code desk_click} payload): nav ids
     * stay in-menu, everything else dispatches. The seq check upstream already
     * dropped clicks against a stale page; a short post-render grace stops a
     * double-click's second hit landing on a freshly painted confirm slot.
     */
    void onClick(int slotId) {
        if (!isAlive() || page == null) return;
        if (System.nanoTime() < clickGraceUntilNanos) return;
        DeskButton clicked = page.buttonAt(slotId);
        if (clicked == null || clicked.actionId() == null) return;
        try {
            switch (clicked.actionId()) {
                case DeskPageSource.BACK -> render(nav.back());
                case DeskPageSource.PREV -> render(nav.goToPage(pageIndex() - 1));
                case DeskPageSource.NEXT -> render(nav.goToPage(pageIndex() + 1));
                case DeskPageSource.YES -> confirmYes();
                case DeskPageSource.NO -> render(nav.back());
                case DeskPageSource.INFO -> { /* the guidance item — no action */ }
                default -> routeAction(clicked);
            }
        } catch (RuntimeException clickFailed) {
            // A failing click must not bubble into the packet handler (that
            // kicks the player) — log and repaint the current page instead.
            com.dwurdy.straja.StrajaMod.LOGGER.warn(
                    com.dwurdy.straja.domain.model.Text.STRAJA_PREFIX
                            + "Desk click failed", clickFailed);
            if (isAlive()) render(nav.current());
        }
    }

    private void routeAction(DeskButton clicked) {
        String actionId = clicked.actionId();
        String section = DeskSections.sectionOfNav(actionId);
        if (section != null) {
            // Section drawers are in-menu navigation: they push a SectionPage
            // onto the trail and never reach performAction (the desk: guard
            // below would refuse the id anyway).
            render(nav.open(new DeskPageSource.SectionPage(section, 0)));
        } else if (actionId.startsWith(DeskPageSource.SURFACE_PREFIX)) {
            dispatchSurface(actionId.substring(DeskPageSource.SURFACE_PREFIX.length()));
        } else if (actionId.startsWith("desk:")) {
            return; // unknown internal nav id — never dispatch
        } else if (actionId.startsWith("main:")) {
            render(nav.home());
        } else if (actionId.startsWith("more:")) {
            // The desk pages all content already; treat «Mai multe» as next page.
            render(nav.goToPage(pageIndex() + 1));
        } else if (actionId.startsWith("faq:")) {
            openFaq(actionId);
        } else if (DeskActionStyle.needsConfirm(actionId)) {
            render(nav.open(new DeskPageSource.ConfirmPage(
                    DeskActionStyle.confirmExecId(actionId),
                    clicked.icon() == null ? "Acțiune" : clicked.icon().name(),
                    DeskActionStyle.consequence(actionId))));
        } else {
            dispatch(actionId);
        }
    }

    /** DA on a confirm page: pop the gate, run the action, refresh the origin. */
    private void confirmYes() {
        if (nav.current() instanceof DeskPageSource.ConfirmPage confirm) {
            DeskPageSource origin = nav.back();
            try {
                NpcRoles.performAction(confirm.actionId(), player, player.serverLevel());
            } catch (RuntimeException actionFailed) {
                com.dwurdy.straja.StrajaMod.LOGGER.warn(
                        com.dwurdy.straja.domain.model.Text.STRAJA_PREFIX
                                + "Desk confirm action failed", actionFailed);
            }
            if (isAlive()) render(origin);
        } else {
            render(nav.current());
        }
    }

    /**
     * Dispatches through the same call the chat-token path uses —
     * parameterized ops re-validate inside via isStillValidForPlayer.
     * An action opening a native form replaces containerMenu; our removed()
     * then fires and the session ends — re-render is skipped by isAlive().
     */
    private void dispatch(String actionId) {
        try {
            NpcRoles.performAction(actionId, player, player.serverLevel());
        } catch (RuntimeException actionFailed) {
            com.dwurdy.straja.StrajaMod.LOGGER.warn(
                    com.dwurdy.straja.domain.model.Text.STRAJA_PREFIX
                            + "Desk action failed", actionFailed);
        }
        if (isAlive()) render(nav.current());
    }

    /**
     * A bound-surface click: re-resolves the published snapshot so a retracted
     * or disabled action fails closed, then routes the raw action id through
     * the provider bridge — the token is minted and consumed by
     * {@code NpcSurfaceActionService}, never by the chat-token service.
     * Actions carrying input fields ride the native form screen via
     * {@code performAction}: a chest grid cannot collect fields.
     */
    private void dispatchSurface(String actionId) {
        if (!(nav.current() instanceof DeskPageSource.SurfacePage surface)
                || surfaceBridge == null) {
            render(nav.current());
            return;
        }
        var resolved = surfaceBridge.resolve(player.getUUID(), surface.binding());
        var action = resolved == null ? null : resolved.actions().stream()
                .filter(candidate -> actionId.equals(candidate.actionId().value()))
                .findFirst().orElse(null);
        if (action == null || !action.enabled()) {
            player.sendSystemMessage(StrajaText.refusal(
                    "straja.npc.action_stale", "straja.remedy.retry"));
            render(nav.current());
            return;
        }
        if (!action.inputs().isEmpty()) {
            dispatch(actionId);
            return;
        }
        NpcActionResult result = surfaceBridge.submit(
                player.getUUID(), surface.binding(), actionId);
        if (result != null && result.status() != NpcActionResult.Status.ACCEPTED) {
            player.sendSystemMessage(Component.literal("⚠ " + result.message()));
        }
        if (isAlive()) render(nav.current());
    }

    private void openFaq(String actionId) {
        NpcFaqSurface.Target target = null;
        var ref = NpcPlayerSurface.parseActionId(actionId);
        if (ref.isPresent() && "faq".equals(ref.get().operation())) {
            target = NpcFaqSurface.parseTarget(ref.get().recordId()).orElse(null);
        }
        var context = NpcRoles.faqContextFor(player, player.serverLevel());
        if (target == null || context == null
                || !NpcFaqSurface.targetAvailable(target, context)) {
            player.sendSystemMessage(StrajaText.refusal(
                    "straja.npc.faq_branch_gone", "straja.remedy.faq"));
            render(nav.current());
            return;
        }
        // «Înapoi la întrebări» emits faq:root_* — pop back to the FAQ root
        // already on the trail instead of pushing a duplicate page.
        if ("root".equals(target.kind()) && nav.current() instanceof DeskPageSource.FaqPage) {
            render(nav.back());
            return;
        }
        render(nav.open(new DeskPageSource.FaqPage(target)));
    }

    /** Rebuilds the page for a source — recomputing live state each time. */
    private void render(DeskPageSource source) {
        if (!isAlive()) return;
        page = renderPage(source);
        seq++;
        PacketDistributor.sendToPlayer(player,
                new DeskPayloads.Page(DeskWire.viewOf(sessionId, seq, page)));
        // Brief click grace after a page swap: a double-click's second hit
        // must not land on a freshly painted confirm/nav slot.
        clickGraceUntilNanos = System.nanoTime() + CLICK_GRACE_NANOS;
    }

    private DeskPage renderPage(DeskPageSource source) {
        try {
            return switch (source) {
                case DeskPageSource.RolePage role -> rolePage(
                        NpcRoles.orderedSurfaceFor(roleId, player, player.serverLevel()),
                        role.page());
                case DeskPageSource.SectionPage section -> sectionPage(
                        NpcRoles.orderedSurfaceFor(roleId, player, player.serverLevel()),
                        section.section(), section.page());
                case DeskPageSource.FaqPage faq -> faqPage(faq.target());
                case DeskPageSource.ConfirmPage confirm -> DeskLayout.confirm(
                        "Confirmă: " + confirm.label(),
                        new DeskIcon(DeskActionStyle.itemFor(confirm.actionId()),
                                confirm.label(), confirm.detail()),
                        true);
                case DeskPageSource.ListPage list -> listPage(list);
                case DeskPageSource.SurfacePage surface -> surfacePage(surface);
            };
        } catch (RuntimeException renderFailed) {
            com.dwurdy.straja.StrajaMod.LOGGER.warn(
                    com.dwurdy.straja.domain.model.Text.STRAJA_PREFIX
                            + "Desk menu render failed", renderFailed);
            return DeskLayout.info("Straja",
                    new DeskIcon(DeskIcon.FALLBACK_ITEM, "Eroare",
                            java.util.List.of("Reîncearcă.")),
                    true);
        }
    }

    /**
     * The role grid — every content action ordered, paged at 45. Only the
     * Secretary desk (Issue 4) swaps the flat grid for section drawers: its
     * surface is too dense to scan — every other role keeps this path
     * unchanged.
     */
    private DeskPage rolePage(NpcPlayerSurface.OrderedSurface ordered, int pageIndex) {
        if (NpcPlayerSurface.routeFor(roleId) == NpcPlayerSurface.RoleRoute.SECRETARY) {
            return secretaryRootPage(ordered, pageIndex);
        }
        var surface = ordered == null ? null : ordered.surface();
        var content = new ArrayList<DeskButton>();
        if (ordered != null) {
            for (var action : ordered.contentActions()) {
                content.add(new DeskButton(
                        DeskActionStyle.icon(action.label(), action.actionId()),
                        action.actionId()));
            }
        }
        return DeskLayout.grid(
                surface == null ? "Straja" : surface.title(),
                DeskActionStyle.header(roleId,
                        surface == null ? "Straja" : surface.title(),
                        surface == null ? "" : surface.guidance()),
                content, pageIndex, nav.depth() > 0);
    }

    /**
     * The Secretary desk top: one drawer button per non-empty section, then
     * the always-essentials — the {@code general} items in emitted order
     * (guard-status, the carnet, V2 statuses, the FAQ entry) and the
     * duty-start/duty-stop verbs pinned at the end whenever the surface
     * emits them, so the most-used click never hides inside a drawer.
     */
    private DeskPage secretaryRootPage(NpcPlayerSurface.OrderedSurface ordered, int pageIndex) {
        var surface = ordered == null ? null : ordered.surface();
        String title = surface == null ? "Straja" : surface.title();
        var content = new ArrayList<DeskButton>();
        var allIds = new ArrayList<String>();
        var general = new ArrayList<NpcPlayerSurface.ChatAction>();
        NpcPlayerSurface.ChatAction dutyStart = null;
        NpcPlayerSurface.ChatAction dutyStop = null;
        if (ordered != null) {
            for (var action : ordered.contentActions()) {
                allIds.add(action.actionId());
                if (DeskSections.GENERAL.equals(DeskSections.sectionFor(action.actionId()))) {
                    general.add(action);
                } else if ("duty-start".equals(action.actionId())) {
                    dutyStart = action;
                } else if ("duty-stop".equals(action.actionId())) {
                    dutyStop = action;
                }
            }
        }
        content.addAll(DeskSections.drawerButtons(allIds));
        for (var action : general) {
            content.add(new DeskButton(
                    DeskActionStyle.icon(action.label(), action.actionId()),
                    action.actionId()));
        }
        if (dutyStart != null) {
            content.add(new DeskButton(
                    DeskActionStyle.icon(dutyStart.label(), dutyStart.actionId()),
                    dutyStart.actionId()));
        }
        if (dutyStop != null) {
            content.add(new DeskButton(
                    DeskActionStyle.icon(dutyStop.label(), dutyStop.actionId()),
                    dutyStop.actionId()));
        }
        return DeskLayout.grid(title, secretaryHeader(ordered, title, allIds),
                content, pageIndex, nav.depth() > 0);
    }

    /**
     * One section drawer page: only the actions {@link DeskSections}
     * classifies under {@code section}, paged at 45 — the Comisar drawer
     * pages the personnel roster the same way. The duty drawer additionally
     * carries the live duty line in its header lore.
     */
    private DeskPage sectionPage(NpcPlayerSurface.OrderedSurface ordered,
                                 String section, int pageIndex) {
        var surface = ordered == null ? null : ordered.surface();
        String baseTitle = surface == null ? "Straja" : surface.title();
        var content = new ArrayList<DeskButton>();
        var ids = new ArrayList<String>();
        if (ordered != null) {
            for (var action : ordered.contentActions()) {
                if (section.equals(DeskSections.sectionFor(action.actionId()))) {
                    ids.add(action.actionId());
                    content.add(new DeskButton(
                            DeskActionStyle.icon(action.label(), action.actionId()),
                            action.actionId()));
                }
            }
        }
        var header = DeskSections.header(section, content.size());
        if (DeskSections.DUTY.equals(section)) {
            String hint = DeskSections.dutyHint(ids);
            if (!hint.isBlank()) {
                var lore = new ArrayList<>(header.lore());
                lore.add(hint);
                header = new DeskIcon(header.itemId(), header.name(), lore);
            }
        }
        return DeskLayout.grid(baseTitle + " — " + DeskSections.title(section),
                header, content, pageIndex, nav.depth() > 0);
    }

    /** The Secretary header plus the one-line live duty state (Issue 4). */
    private DeskIcon secretaryHeader(NpcPlayerSurface.OrderedSurface ordered,
                                     String title, List<String> actionIds) {
        String guidance = ordered != null && ordered.surface() != null
                ? ordered.surface().guidance() : "";
        var header = DeskActionStyle.header(roleId, title, guidance);
        String hint = DeskSections.dutyHint(actionIds);
        if (hint.isBlank()) return header;
        var lore = new ArrayList<>(header.lore());
        lore.add(hint);
        return new DeskIcon(header.itemId(), header.name(), lore);
    }

    /**
     * A flat admin-tool grid — the same click → performAction dispatch a
     * role desk uses; only the header (wand icon + wrapped menu title)
     * differs.
     */
    private DeskPage listPage(DeskPageSource.ListPage list) {
        return DeskLayout.grid(list.title(), toolHeader(list.title()),
                list.items(), list.page(), nav.depth() > 0);
    }

    private DeskIcon toolHeader(String title) {
        var lore = new ArrayList<>(DeskLayout.wrapLore(title, DeskLayout.LORE_WRAP));
        lore.add("Alege o acțiune — se execută direct.");
        return new DeskIcon("straja:npc_wand", "Instrumente", List.copyOf(lore));
    }

    /**
     * The bound-NPC grid: only enabled snapshot actions render, each wrapped
     * in the {@code desk:surface:} nav prefix so clicks route through the
     * provider token bridge instead of {@code performAction}. A retracted
     * surface renders a closed info grid — fail closed, never a dead menu.
     */
    private DeskPage surfacePage(DeskPageSource.SurfacePage surface) {
        var resolved = surfaceBridge == null ? null
                : surfaceBridge.resolve(player.getUUID(), surface.binding());
        if (resolved == null) {
            return DeskLayout.grid("Suprafață retrasă",
                    new DeskIcon(DeskIcon.FALLBACK_ITEM, "Suprafață retrasă",
                            List.of("Legătura a fost retrasă sau s-a",
                                    "schimbat. Închide meniul.")),
                    List.of(), 0, nav.depth() > 0);
        }
        var content = new ArrayList<DeskButton>();
        for (var action : resolved.actions()) {
            if (!action.enabled()) continue;
            content.add(new DeskButton(
                    DeskActionStyle.icon(action.label(), action.actionId().value()),
                    DeskPageSource.SURFACE_PREFIX + action.actionId().value()));
        }
        String title = resolved.title() == null || resolved.title().isBlank()
                ? "Straja" : resolved.title();
        return DeskLayout.grid(title,
                DeskActionStyle.header(surface.binding().roleId(), title, "Alege o acțiune."),
                content, surface.page(), nav.depth() > 0);
    }

    private DeskPage faqPage(NpcFaqSurface.Target target) {
        var context = NpcRoles.faqContextFor(player, player.serverLevel());
        if (context == null || !NpcFaqSurface.targetAvailable(target, context)) {
            return rolePage(NpcRoles.orderedSurfaceFor(roleId, player, player.serverLevel()), 0);
        }
        return switch (target.kind()) {
            case "root" -> faqGrid(NpcFaqSurface.root(target.origin(), context));
            case "menu" -> faqGrid(NpcFaqSurface.menu(target.origin(), context, target.topic()));
            case "answer" -> {
                String answer = NpcFaqSurface.answer(
                        target.origin(), target.topic(), target.question(), context);
                String question = questionLabel(target, context);
                yield DeskLayout.info("FAQ — " + question,
                        new DeskIcon("minecraft:writable_book", question,
                                DeskLayout.wrapLore(answer, DeskLayout.LORE_WRAP)),
                        true);
            }
            default -> faqGrid(NpcFaqSurface.root(target.origin(), context));
        };
    }

    private DeskPage faqGrid(NpcPlayerSurface.RoleSurface surface) {
        var content = new ArrayList<DeskButton>();
        for (var action : surface.actions()) {
            content.add(new DeskButton(
                    DeskActionStyle.icon(action.label(), action.actionId()),
                    action.actionId()));
        }
        return DeskLayout.grid(surface.title(),
                DeskActionStyle.header(roleId, surface.title(), surface.guidance()),
                content, 0, true);
    }

    /** The visible question label behind an FAQ answer target. */
    private String questionLabel(NpcFaqSurface.Target target, NpcFaqSurface.Context context) {
        String id = "faq:answer_" + target.topic() + "_" + target.question()
                + "_" + NpcFaqSurface.roleKey(target.origin());
        for (var action : NpcFaqSurface.menu(target.origin(), context, target.topic()).actions()) {
            if (id.equals(action.actionId())) return action.label();
        }
        return "Răspuns";
    }

    private int pageIndex() {
        if (nav.current() instanceof DeskPageSource.RolePage role) return role.page();
        if (nav.current() instanceof DeskPageSource.SectionPage section) return section.page();
        if (nav.current() instanceof DeskPageSource.ListPage list) return list.page();
        if (nav.current() instanceof DeskPageSource.SurfacePage surface) return surface.page();
        return 0;
    }
}
