package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.adapter.in.form.FormPayloads;
import com.dwurdy.straja.adapter.in.form.StrajaFormMenu;
import com.dwurdy.straja.adapter.out.theme.GuiTheme;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Native client form: renders the server-provided view, sends payloads only. */
public class StrajaFormScreen extends AbstractContainerScreen<StrajaFormMenu> {
    private static final int SINGLE_LINE_HEIGHT = 20;
    private static final int MULTI_LINE_HEIGHT = 56;
    private static final int LABEL_HEIGHT = 11;
    private static final int FIELD_GAP = 8;
    private static final int FOOTER_HEIGHT = 28;

    // Multiline boxes carry their own scrollbar, so they can shrink hard.
    private static final int MIN_MULTI_LINE_HEIGHT = 14;
    private static final int SCREEN_MARGIN = 8;

    private final List<Input> inputs = new ArrayList<>();
    private final ItemStack headerIcon;
    private int promptHeight;
    private int multiLineHeight = MULTI_LINE_HEIGHT;
    private int fieldGap = FIELD_GAP;
    private boolean submitted;
    private boolean cancelSent;

    private record Input(Field field, Supplier<String> value, int labelY) {}

    public StrajaFormScreen(StrajaFormMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, StrajaFormMenu.textComponent(menu.view().title()));
        this.imageWidth = 240;
        this.imageHeight = GuiTheme.GUI_HEIGHT;
        this.headerIcon = iconStack();
    }

    /** Tier-1 item icon for the header (act_input → carbon_paper); empty if unregistered. */
    private static ItemStack iconStack() {
        String itemId = GuiTheme.iconItemFallback(GuiTheme.ICON_INPUT);
        if (itemId == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Override
    protected void init() {
        // The prompt sits in the body band, so its wrapped height is part of
        // the window size; the panel must still fit the actual screen — a
        // 4-field multiline form (REPORT_SUBMIT) would otherwise push the
        // footer buttons below the viewport on GUI scale ≥3.
        int contentWidth = imageWidth - GuiTheme.MARGIN * 2;
        this.promptHeight = font
                .split(StrajaFormMenu.textComponent(getMenu().view().prompt()), contentWidth)
                .size() * font.lineHeight;
        int fieldCount = 0;
        int multiCount = 0;
        int singleCount = 0;
        for (Field field : getMenu().view().fields()) {
            fieldCount++;
            if (field.multiline()) multiCount++; else singleCount++;
        }
        int budget = height - SCREEN_MARGIN * 2 - GuiTheme.BODY_Y - promptHeight - 8
                - FOOTER_HEIGHT - 4;
        this.fieldGap = FIELD_GAP;
        this.multiLineHeight = fitMultiLine(budget, fieldCount, multiCount,
                singleCount * SINGLE_LINE_HEIGHT);
        // If minimum-height multiline boxes still overflow, tighten the field
        // gap before accepting the overflow (footer stays clickable either way).
        if (fieldsHeight(fieldCount, multiCount, singleCount) > budget) {
            this.fieldGap = 4;
            this.multiLineHeight = fitMultiLine(budget, fieldCount, multiCount,
                    singleCount * SINGLE_LINE_HEIGHT);
        }
        this.imageHeight = Math.min(
                GuiTheme.BODY_Y + promptHeight + 8
                        + fieldsHeight(fieldCount, multiCount, singleCount) + FOOTER_HEIGHT + 4,
                height - SCREEN_MARGIN * 2);
        super.init();
        inputs.clear();
        int x = leftPos + GuiTheme.MARGIN;
        int y = topPos + GuiTheme.BODY_Y + promptHeight + 8;
        int width = imageWidth - GuiTheme.MARGIN * 2;
        for (Field field : getMenu().view().fields()) {
            int labelY = y - topPos;
            if (field.multiline()) {
                MultiLineEditBox box = new MultiLineEditBox(font, x, y + LABEL_HEIGHT, width,
                        multiLineHeight, Component.empty(),
                        Component.literal(field.label()));
                box.setCharacterLimit(field.maxLength());
                addRenderableWidget(box);
                inputs.add(new Input(field, box::getValue, labelY));
                y += LABEL_HEIGHT + multiLineHeight + fieldGap;
            } else {
                EditBox box = new EditBox(font, x, y + LABEL_HEIGHT, width, SINGLE_LINE_HEIGHT,
                        Component.literal(field.label()));
                box.setMaxLength(field.maxLength());
                addRenderableWidget(box);
                inputs.add(new Input(field, box::getValue, labelY));
                y += LABEL_HEIGHT + SINGLE_LINE_HEIGHT + fieldGap;
            }
        }
        int footerY = topPos + imageHeight - FOOTER_HEIGHT;
        addRenderableWidget(Button.builder(Component.translatable("straja.form.submit"), b -> submit())
                .bounds(x, footerY, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("straja.form.cancel"), b -> onClose())
                .bounds(x + 88, footerY, 80, 20).build());
    }

    /** Multiline box height that fits the field budget (boxes scroll internally). */
    private int fitMultiLine(int budget, int fieldCount, int multiCount, int singleHeight) {
        if (multiCount == 0) return MULTI_LINE_HEIGHT;
        int fixed = fieldCount * (LABEL_HEIGHT + fieldGap) + singleHeight;
        return Math.min(MULTI_LINE_HEIGHT,
                Math.max(MIN_MULTI_LINE_HEIGHT, (budget - fixed) / multiCount));
    }

    private int fieldsHeight(int fieldCount, int multiCount, int singleCount) {
        return fieldCount * (LABEL_HEIGHT + fieldGap)
                + multiCount * multiLineHeight + singleCount * SINGLE_LINE_HEIGHT;
    }

    private void submit() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Input input : inputs) {
            String value = input.value().get();
            if (value == null || value.isBlank()) return;
            values.put(input.field().id(), value);
        }
        submitted = true;
        PacketDistributor.sendToServer(
                new FormPayloads.Submit(getMenu().view().sessionId(), values));
    }

    @Override
    public void onClose() {
        if (!submitted && !cancelSent) {
            cancelSent = true;
            PacketDistributor.sendToServer(
                    new FormPayloads.Cancel(getMenu().view().sessionId()));
        }
        super.onClose();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                0xFF_000000 | GuiTheme.COLOR_NIGHT);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight,
                0xFF_000000 | GuiTheme.COLOR_LEATHER);
        graphics.fill(leftPos + GuiTheme.MARGIN, topPos + GuiTheme.RULE_Y,
                leftPos + imageWidth - GuiTheme.MARGIN, topPos + GuiTheme.RULE_Y + 1,
                0xFF_000000 | GuiTheme.COLOR_LEATHER);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!headerIcon.isEmpty()) {
            graphics.renderItem(headerIcon, GuiTheme.MARGIN, GuiTheme.HEADER_Y);
        }
        String fittedTitle = font.plainSubstrByWidth(title.getString(),
                imageWidth - GuiTheme.TITLE_X - GuiTheme.MARGIN);
        graphics.drawString(font, fittedTitle, GuiTheme.TITLE_X, GuiTheme.HEADER_Y + 2,
                0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT, false);
        graphics.drawWordWrap(font, StrajaFormMenu.textComponent(getMenu().view().prompt()),
                GuiTheme.MARGIN, GuiTheme.BODY_Y, imageWidth - GuiTheme.MARGIN * 2,
                0xFF_000000 | GuiTheme.COLOR_PAPER_DIM);
        for (Input input : inputs) {
            String label = font.plainSubstrByWidth(input.field().label(),
                    imageWidth - GuiTheme.MARGIN * 2);
            graphics.drawString(font, label, GuiTheme.MARGIN, input.labelY(),
                    0xFF_000000 | GuiTheme.COLOR_PAPER_DIM, false);
        }
    }
}
