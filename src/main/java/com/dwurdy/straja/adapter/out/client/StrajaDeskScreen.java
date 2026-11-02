package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.adapter.in.menu.DeskGuiMenu;
import com.dwurdy.straja.adapter.in.menu.DeskIcon;
import com.dwurdy.straja.adapter.in.menu.DeskWire;
import com.dwurdy.straja.adapter.in.menu.DeskLayout;
import com.dwurdy.straja.adapter.in.menu.DeskPayloads;
import com.dwurdy.straja.adapter.out.theme.GuiTheme;
import java.util.ArrayList;
import java.util.HashMap;
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
 * as a readable menu — one full-width labeled row per action (icon, name,
 * one-line hint), the same row treatment for footer nav («Înapoi», page
 * arrows), and DA/NU confirm buttons on confirm pages. A press sends only
 * the row's slot back; the server resolves the action against the page it
 * rendered, so this screen never sees an action id.
 */
public class StrajaDeskScreen extends AbstractContainerScreen<DeskGuiMenu> {
    private static final int PAD = GuiTheme.MARGIN;
    private static final int PANEL_W = 252;
    private static final int CONTENT_Y = 58;
    private static final int CONFIRM_BODY_H = 74;
    private static final int CONFIRM_BTN_Y = CONTENT_Y + CONFIRM_BODY_H + 4;
    private static final int FOOTER_GAP = 4;
    private static final int BG_ROW = 0xFF1E1E38;
    private static final int BG_ROW_HOVER = 0xFF2E2E50;

    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private int rowH = 22;
    private int footerY;
    private DeskWire.Tile headerTile;
    private DeskWire.Tile detailTile;

    public StrajaDeskScreen(DeskGuiMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
    }

    @Override
    protected void init() {
        // Labeled rows replace the chest-grid tiles; rows shrink on short
        // viewports and dense pages so the footer rows stay on screen.
        this.imageWidth = PANEL_W;
        // Size the panel to the page: list pages hug the last used content
        // row; confirm/info pages reserve the body block (and the DA/NU row
        // on confirms); footer rows exist only when nav tiles are present.
        int contentRows = 0;
        int footerRows = 0;
        boolean confirm = false;
        boolean detail = false;
        DeskWire.View view = getMenu().view();
        if (view != null) {
            for (DeskWire.Tile cell : view.tiles()) {
                if (cell.slot() == DeskLayout.INFO_SLOT) continue;
                if (cell.slot() == DeskLayout.DETAIL_SLOT) {
                    detail = true;
                    continue;
                }
                if (cell.slot() == DeskLayout.YES_SLOT
                        || cell.slot() == DeskLayout.NO_SLOT) {
                    confirm = true;
                } else if (cell.slot() >= 0 && cell.slot() < DeskLayout.CONTENT_SLOTS) {
                    contentRows = Math.max(contentRows, cell.slot() + 1);
                } else if (cell.slot() >= DeskLayout.BACK_SLOT) {
                    footerRows++;
                }
            }
        }
        int contentH;
        if (confirm || detail) {
            contentH = confirm ? CONFIRM_BODY_H + 26 : CONFIRM_BODY_H;
            // The body is a fixed block — only footer rows share the row math.
            int avail = height - CONTENT_Y - contentH - FOOTER_GAP - 6;
            this.rowH = footerRows > 0
                    ? Math.min(22, Math.max(16, avail / footerRows)) : 22;
        } else {
            // Every visible band is a row: distribute the viewport budget.
            int rowUnits = Math.max(contentRows, 1) + footerRows;
            this.rowH = Math.min(22, Math.max(16,
                    (height - CONTENT_Y - FOOTER_GAP - 6) / rowUnits));
            contentH = Math.max(contentRows, 1) * rowH;
        }
        this.footerY = CONTENT_Y + contentH + FOOTER_GAP;
        this.imageHeight = footerY + footerRows * rowH + 6;
        super.init();
        rebuildRows();
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

    private void rebuildRows() {
        clearWidgets();
        iconCache.clear();
        headerTile = null;
        detailTile = null;
        DeskWire.Tile yesTile = null;
        DeskWire.Tile noTile = null;
        var footerTiles = new ArrayList<DeskWire.Tile>();
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
            if (cell.slot() == DeskLayout.YES_SLOT) {
                yesTile = cell;
                continue;
            }
            if (cell.slot() == DeskLayout.NO_SLOT) {
                noTile = cell;
                continue;
            }
            if (cell.slot() >= 0 && cell.slot() < DeskLayout.CONTENT_SLOTS) {
                int x = leftPos + PAD;
                int y = topPos + CONTENT_Y + cell.slot() * rowH;
                addRenderableWidget(new DeskRow(x, y,
                        imageWidth - PAD * 2, rowH - 2, cell));
            } else if (cell.slot() >= DeskLayout.BACK_SLOT) {
                footerTiles.add(cell);
            }
            // Anything else — a dead-range slot — is dropped, not rendered:
            // the client fails closed on a malformed wire view.
        }
        // Footer nav rows share the content row treatment («Înapoi», page
        // arrows) — same labeled-row affordance, laid in view order.
        for (int j = 0; j < footerTiles.size(); j++) {
            DeskWire.Tile cell = footerTiles.get(j);
            int y = topPos + footerY + j * rowH;
            addRenderableWidget(new DeskRow(leftPos + PAD, y,
                    imageWidth - PAD * 2, rowH - 2, cell));
        }
        // Confirm row: two labeled buttons centered under the body —
        // DA carries the authority color, NU stays steel.
        if (yesTile != null || noTile != null) {
            int bw = 64, gap = 12;
            int bx = leftPos + (imageWidth - 2 * bw - gap) / 2;
            int by = topPos + CONFIRM_BTN_Y;
            if (yesTile != null) {
                addRenderableWidget(new DeskButton(bx, by, bw, 20, yesTile));
            }
            if (noTile != null) {
                addRenderableWidget(new DeskButton(bx + bw + gap, by, bw, 20, noTile));
            }
        }
        // The header band keeps the full guidance on hover — the two visible
        // lines are a preview, the tooltip carries every lore line.
        if (headerTile != null) {
            addRenderableWidget(new HeaderInfo(leftPos + PAD, topPos + 2,
                    imageWidth - PAD * 2, CONTENT_Y - 6, headerTile));
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
                if (y > CONTENT_Y + CONFIRM_BODY_H - font.lineHeight) return;
                graphics.drawString(font, wrapped, GuiTheme.MARGIN, y,
                        0xFF_000000 | GuiTheme.COLOR_PAPER_DIM, false);
                y += font.lineHeight;
            }
        }
    }

    /** Name first, then lore — one MutableComponent joined with newlines. */
    private static Tooltip rowTooltip(DeskWire.Tile data) {
        MutableComponent tip = null;
        if (!data.name().isBlank()) tip = Component.literal(data.name());
        for (String lore : data.lore()) {
            MutableComponent line = Component.literal(lore).withStyle(
                    Style.EMPTY.withColor(GuiTheme.COLOR_PAPER_DIM));
            tip = tip == null ? line : tip.append("\n").append(line);
        }
        return tip == null ? null : Tooltip.create(tip);
    }

    /** One labeled row of the desk list — the action's name is always visible. */
    private final class DeskRow extends AbstractWidget {
        private final DeskWire.Tile data;
        private ItemStack stack;

        DeskRow(int x, int y, int w, int h, DeskWire.Tile data) {
            super(x, y, w, h, Component.literal(data.name()));
            this.data = data;
            setTooltip(rowTooltip(data));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            press(data);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            boolean lit = isHovered() || isFocused();
            graphics.fill(getX(), getY(), getX() + width, getY() + height,
                    lit ? BG_ROW_HOVER : BG_ROW);
            graphics.renderOutline(getX(), getY(), width, height,
                    0xFF_000000 | (lit
                            ? GuiTheme.COLOR_BRASS : GuiTheme.COLOR_LEATHER));
            if (stack == null) stack = iconFor(data.itemId());
            int iconSize = 16;
            if (!stack.isEmpty()) {
                graphics.renderFakeItem(stack,
                        getX() + 3, getY() + (height - iconSize) / 2);
            }
            int textX = getX() + 3 + iconSize + 5;
            int textW = getX() + width - 4 - textX;
            String hint = data.lore().isEmpty() ? "" : data.lore().get(0);
            // Two text lines need ~20px of row; shorter rows show the name only
            // (the tooltip still carries every lore line).
            if (hint.isBlank() || height < 20) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(data.name(), textW),
                        textX, getY() + (height - font.lineHeight) / 2 + 1,
                        0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT, false);
            } else {
                graphics.drawString(font,
                        font.plainSubstrByWidth(data.name(), textW),
                        textX, getY() + 1,
                        0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT, false);
                graphics.drawString(font,
                        font.plainSubstrByWidth(hint, textW),
                        textX, getY() + height - font.lineHeight - 1,
                        0xFF_000000 | GuiTheme.COLOR_PAPER_DIM, false);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** A labeled DA/NU button — text on the theme frame, centered under the body. */
    private final class DeskButton extends AbstractWidget {
        private final DeskWire.Tile data;

        DeskButton(int x, int y, int w, int h, DeskWire.Tile data) {
            super(x, y, w, h, Component.literal(data.name()));
            this.data = data;
            setTooltip(rowTooltip(data));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            press(data);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            boolean lit = isHovered() || isFocused();
            graphics.fill(getX(), getY(), getX() + width, getY() + height,
                    lit ? BG_ROW_HOVER : BG_ROW);
            graphics.renderOutline(getX(), getY(), width, height,
                    0xFF_000000 | (lit
                            ? GuiTheme.COLOR_BRASS : GuiTheme.COLOR_LEATHER));
            graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(data.name(), width - 6),
                    getX() + width / 2, getY() + (height - font.lineHeight) / 2 + 1,
                    0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * Invisible header band: hovering anywhere in the title/guidance area
     * shows the complete guidance (name + every lore line), so the two
     * visible lines are a preview, never a loss.
     */
    private static final class HeaderInfo extends AbstractWidget {
        HeaderInfo(int x, int y, int w, int h, DeskWire.Tile data) {
            super(x, y, w, h, Component.literal(data.name()));
            setTooltip(rowTooltip(data));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {}

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {}

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }
}
