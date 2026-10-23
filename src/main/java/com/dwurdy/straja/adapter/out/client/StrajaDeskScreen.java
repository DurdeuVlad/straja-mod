package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.adapter.in.menu.DeskGuiMenu;
import com.dwurdy.straja.adapter.in.menu.DeskIcon;
import com.dwurdy.straja.adapter.in.menu.DeskWire;
import com.dwurdy.straja.adapter.in.menu.DeskLayout;
import com.dwurdy.straja.adapter.in.menu.DeskPayloads;
import com.dwurdy.straja.adapter.out.theme.GuiTheme;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The purpose-built desk screen. Renders the server's {@link DeskWire.View}
 * as real widgets — icon tiles with instant hover info and tooltips, a live
 * title, the guidance header, the detail body on confirm/info pages, and the
 * footer nav row. A press sends only the widget's slot back; the server
 * resolves the action against the page it rendered, so this screen never sees
 * an action id.
 */
public class StrajaDeskScreen extends AbstractContainerScreen<DeskGuiMenu> {
    private static final int PAD = GuiTheme.MARGIN;
    private static final int CONTENT_Y = 60;
    private static final int INFOBAR_GAP = 24;
    private static final int BG_TILE = 0xFF1E1E38;
    private static final int BG_TILE_HOVER = 0xFF2E2E50;

    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private final List<DeskTile> deskTiles = new ArrayList<>();
    private int tile = 24;
    private int infoBarY;
    private int footerY;
    private DeskWire.Tile headerTile;
    private DeskWire.Tile detailTile;

    public StrajaDeskScreen(DeskGuiMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = DeskLayout.COLUMNS * tile + PAD * 2;
    }

    @Override
    protected void init() {
        // Grid rows like the chest desk it replaces; shrink tiles on short
        // viewports so the footer never falls off the bottom edge.
        this.tile = height >= 250 ? 26 : (height >= 232 ? 24 : 22);
        this.imageWidth = DeskLayout.COLUMNS * tile + PAD * 2;
        // Size the panel to the page: the info bar hugs the last used content
        // row and the footer row exists only when nav tiles are present, so
        // small pages never show a dead band at the bottom.
        int rows = 1;
        boolean footer = false;
        DeskWire.View view = getMenu().view();
        if (view != null) {
            for (DeskWire.Tile cell : view.tiles()) {
                if (cell.slot() == DeskLayout.INFO_SLOT
                        || cell.slot() == DeskLayout.DETAIL_SLOT) continue;
                if (cell.slot() < DeskLayout.CONTENT_SLOTS) {
                    rows = Math.max(rows, cell.slot() / DeskLayout.COLUMNS + 1);
                } else {
                    footer = true;
                }
            }
        }
        this.infoBarY = CONTENT_Y + rows * tile + 4;
        this.footerY = infoBarY + INFOBAR_GAP;
        this.imageHeight = footer
                ? footerY + tile + 8
                : infoBarY + 2 * font.lineHeight + 10;
        super.init();
        rebuildTiles();
    }

    /**
     * Client entry for {@code desk_page} pushes: applies the fresh view to the
     * open screen only when the session ids match, then re-lays out widgets.
     */
    public static void acceptPage(DeskPayloads.Page payload) {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof StrajaDeskScreen screen)) return;
        DeskWire.View view = screen.getMenu().view();
        if (view == null || !view.sessionId().equals(payload.view().sessionId())) return;
        screen.getMenu().applyView(payload.view());
        screen.init(mc, mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight());
    }

    private void rebuildTiles() {
        clearWidgets();
        deskTiles.clear();
        iconCache.clear();
        headerTile = null;
        detailTile = null;
        DeskWire.View view = getMenu().view();
        if (view == null) return;
        for (DeskWire.Tile cell : view.tiles()) {
            // The guidance item becomes the header text; the detail item
            // becomes the body block — both read instantly, no hover needed.
            if (cell.slot() == DeskLayout.INFO_SLOT) {
                headerTile = cell;
                continue;
            }
            if (cell.slot() == DeskLayout.DETAIL_SLOT) {
                detailTile = cell;
                continue;
            }
            int col = cell.slot() % DeskLayout.COLUMNS;
            int row = cell.slot() / DeskLayout.COLUMNS;
            int x = leftPos + PAD + col * tile;
            int y = topPos + (cell.slot() < DeskLayout.CONTENT_SLOTS
                    ? CONTENT_Y + row * tile : footerY);
            DeskTile widget = new DeskTile(x, y, cell);
            addRenderableWidget(widget);
            deskTiles.add(widget);
        }
    }

    private ItemStack iconFor(String itemId) {
        return iconCache.computeIfAbsent(itemId, id -> {
            Item item = null;
            try {
                item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            } catch (RuntimeException malformed) {
                // unparseable server-provided id — fall through to fallback
            }
            if (item == null || item == Items.AIR) {
                item = BuiltInRegistries.ITEM.get(
                        ResourceLocation.parse(DeskIcon.FALLBACK_ITEM));
            }
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    private void press(DeskWire.Tile cell) {
        DeskWire.View view = getMenu().view();
        if (view == null) return;
        PacketDistributor.sendToServer(
                new DeskPayloads.Click(view.sessionId(), view.seq(), cell.slot()));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                0xFF_000000 | GuiTheme.COLOR_NIGHT);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight,
                0xFF_000000 | GuiTheme.COLOR_LEATHER);
        graphics.fill(leftPos + PAD, topPos + GuiTheme.RULE_Y,
                leftPos + imageWidth - PAD, topPos + GuiTheme.RULE_Y + 1,
                0xFF_000000 | GuiTheme.COLOR_LEATHER);
        graphics.fill(leftPos + PAD, topPos + infoBarY,
                leftPos + imageWidth - PAD, topPos + infoBarY + 1,
                0xFF_000000 | GuiTheme.COLOR_LEATHER);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        DeskWire.View view = getMenu().view();
        if (view == null) return;
        int textX = GuiTheme.MARGIN;
        if (headerTile != null) {
            graphics.renderItem(iconFor(headerTile.itemId()), PAD, GuiTheme.HEADER_Y - 2);
            textX = GuiTheme.TITLE_X;
        }
        String fitted = font.plainSubstrByWidth(view.title(),
                imageWidth - textX - GuiTheme.MARGIN);
        graphics.drawString(font, fitted, textX, GuiTheme.HEADER_Y + 2,
                0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT, false);
        if (headerTile != null) {
            var lines = new ArrayList<String>();
            // The guidance name repeats the window title on most pages —
            // render it only when it actually adds information.
            if (!headerTile.name().isBlank() && !headerTile.name().equals(view.title()))
                lines.add(headerTile.name());
            lines.addAll(headerTile.lore());
            int y = GuiTheme.RULE_Y + 5;
            for (String line : lines) {
                for (var wrapped : font.split(Component.literal(line),
                        imageWidth - GuiTheme.MARGIN * 2)) {
                    if (y > CONTENT_Y - font.lineHeight - 2) break;
                    graphics.drawString(font, wrapped, GuiTheme.MARGIN, y,
                            0xFF_000000 | GuiTheme.COLOR_PAPER_DIM, false);
                    y += font.lineHeight;
                }
            }
        }
        if (detailTile != null) {
            renderDetailBody(graphics);
        }
        renderInfoBar(graphics);
    }

    /** The confirm/FAQ body: subject icon + name + lore, always visible. */
    private void renderDetailBody(GuiGraphics graphics) {
        // renderLabels is pose-translated to (leftPos, topPos) — panel-relative
        // coordinates only, no screen-space offsets here.
        int y = CONTENT_Y + 4;
        graphics.renderItem(iconFor(detailTile.itemId()), GuiTheme.MARGIN, y - 2);
        graphics.drawString(font,
                font.plainSubstrByWidth(detailTile.name(), imageWidth - GuiTheme.MARGIN * 3 - 20),
                GuiTheme.MARGIN + 20, y + 4,
                0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT, false);
        y += 20;
        for (String line : detailTile.lore()) {
            for (var wrapped : font.split(Component.literal(line),
                    imageWidth - GuiTheme.MARGIN * 2)) {
                if (y > CONTENT_Y + 2 * tile - font.lineHeight) return;
                graphics.drawString(font, wrapped, GuiTheme.MARGIN, y,
                        0xFF_000000 | GuiTheme.COLOR_PAPER_DIM, false);
                y += font.lineHeight;
            }
        }
    }

    /** Instant readout: hovered tile's name + first lore line. */
    private void renderInfoBar(GuiGraphics graphics) {
        DeskTile hovered = null;
        for (DeskTile widget : deskTiles) {
            if (widget.isHovered()) { hovered = widget; break; }
        }
        int barY = infoBarY + 5;
        if (hovered == null) return;
        graphics.drawString(font,
                font.plainSubstrByWidth(hovered.data.name(),
                        imageWidth - GuiTheme.MARGIN * 2),
                GuiTheme.MARGIN, barY,
                0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT, false);
        if (!hovered.data.lore().isEmpty()) {
            graphics.drawString(font,
                    font.plainSubstrByWidth(hovered.data.lore().get(0),
                            imageWidth - GuiTheme.MARGIN * 2),
                    GuiTheme.MARGIN, barY + font.lineHeight,
                    0xFF_000000 | GuiTheme.COLOR_PAPER_DIM, false);
        }
    }

    /** One clickable cell of the desk grid. */
    private final class DeskTile extends AbstractWidget {
        private final DeskWire.Tile data;
        private ItemStack stack;

        DeskTile(int x, int y, DeskWire.Tile data) {
            super(x, y, tile, tile, Component.literal(data.name()));
            this.data = data;
            // Tooltip.create splits the component on newlines into lines —
            // name first, then lore, one MutableComponent joined with "\n".
            MutableComponent tip = null;
            if (!data.name().isBlank()) tip = Component.literal(data.name());
            for (String lore : data.lore()) {
                MutableComponent line = Component.literal(lore).withStyle(
                        Style.EMPTY.withColor(GuiTheme.COLOR_PAPER_DIM));
                tip = tip == null ? line : tip.append("\n").append(line);
            }
            if (tip != null) setTooltip(Tooltip.create(tip));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            press(data);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height,
                    isHovered() ? BG_TILE_HOVER : BG_TILE);
            graphics.renderOutline(getX(), getY(), width, height,
                    0xFF_000000 | (isHovered()
                            ? GuiTheme.COLOR_BRASS : GuiTheme.COLOR_LEATHER));
            if (stack == null) stack = iconFor(data.itemId());
            if (!stack.isEmpty()) {
                graphics.renderFakeItem(stack,
                        getX() + (width - 16) / 2, getY() + (height - 16) / 2);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
