package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.adapter.in.StrajaText;
import com.dwurdy.straja.adapter.in.npc.NpcFaqSurface;
import com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface;
import com.dwurdy.straja.adapter.in.npc.NpcRoles;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ClickType;

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
    private final DeskNavigation nav = new DeskNavigation();
    private DeskMenu menu;
    private DeskPage page;
    private boolean alive = true;
    private long clickGraceUntilNanos;

    DeskMenuSession(ServerPlayer player, String roleId) {
        this.player = player;
        this.roleId = roleId;
    }

    /**
     * Opens the chest menu once, rendering page 0 of the ordered surface.
     * Subsequent page turns reuse this container; {@link #isAlive} ends the
     * session when the menu is replaced (e.g. a form opens) or removed.
     */
    boolean open(NpcPlayerSurface.OrderedSurface ordered) {
        page = rolePage(ordered, 0);
        // The container title is fixed at open — keep the plain surface title:
        // a baked "— 1/2" suffix would stay misleading on every later page.
        String title = page.title();
        int pageMark = title.lastIndexOf(" — ");
        if (pageMark > 0) title = title.substring(0, pageMark);
        var opened = player.openMenu(new SimpleMenuProvider(
                (containerId, inv, p) -> {
                    menu = new DeskMenu(containerId, inv, this, DeskLayout.ROWS);
                    paint(menu.grid(), page);
                    return menu;
                },
                Component.literal(title.length() > MAX_TITLE
                        ? title.substring(0, MAX_TITLE) : title)));
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
     * Routes one grid click: nav ids stay in-menu, everything else dispatches.
     * Only a single left {@code PICKUP} acts — double-click pickup-all, throw
     * (Q), hotbar swap, clone and drag gestures never fire actions, so the
     * DA/NU gate can't be stormed through by an incidental gesture; a short
     * post-render grace stops a double-click's second hit landing on a freshly
     * painted confirm slot.
     */
    void onClick(int slotId, int button, ClickType clickType) {
        if (!isAlive() || page == null) return;
        if (clickType != ClickType.PICKUP || button != 0) return;
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
            render(nav.open(new DeskPageSource.ConfirmPage(actionId,
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
        paint(menu.grid(), page);
        menu.broadcastChanges();
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
        return 0;
    }

    private static void paint(SimpleContainer grid, DeskPage deskPage) {
        grid.clearContent();
        for (DeskButton button : deskPage.buttons()) {
            if (button.slot() >= 0 && button.slot() < grid.getContainerSize()) {
                grid.setItem(button.slot(), DeskIcons.stack(button.icon()));
            }
        }
    }
}
