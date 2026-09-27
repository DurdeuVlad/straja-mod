package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.adapter.in.form.FormPayloads;
import com.dwurdy.straja.adapter.in.form.StrajaFormMenu;
import com.dwurdy.straja.adapter.out.npc.customnpcs.GuiTheme;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
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

    private final List<Input> inputs = new ArrayList<>();
    private final ItemStack headerIcon;
    private final int promptHeight;
    private boolean submitted;
    private boolean cancelSent;

    private record Input(Field field, Supplier<String> value, int labelY) {}

    public StrajaFormScreen(StrajaFormMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, StrajaFormMenu.textComponent(menu.view().title()));
        this.imageWidth = 240;
        int contentWidth = imageWidth - GuiTheme.MARGIN * 2;
        // The prompt sits in the header band's body (like the CustomNPCs input
        // surface), so its wrapped height must be part of the window size.
        this.promptHeight = Minecraft.getInstance().font
                .split(StrajaFormMenu.textComponent(menu.view().prompt()), contentWidth)
                .size() * Minecraft.getInstance().font.lineHeight;
        int fieldsHeight = 0;
        for (Field field : menu.view().fields()) {
            fieldsHeight += LABEL_HEIGHT + (field.multiline() ? MULTI_LINE_HEIGHT : SINGLE_LINE_HEIGHT)
                    + FIELD_GAP;
        }
        this.imageHeight = GuiTheme.BODY_Y + promptHeight + 8 + fieldsHeight + FOOTER_HEIGHT + 4;
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
        super.init();
        inputs.clear();
        int x = leftPos + GuiTheme.MARGIN;
        int y = topPos + GuiTheme.BODY_Y + promptHeight + 8;
        int width = imageWidth - GuiTheme.MARGIN * 2;
        for (Field field : getMenu().view().fields()) {
            int labelY = y - topPos;
            if (field.multiline()) {
                MultiLineEditBox box = new MultiLineEditBox(font, x, y + LABEL_HEIGHT, width,
                        MULTI_LINE_HEIGHT, Component.literal(field.label()),
                        Component.literal(field.label()));
                box.setCharacterLimit(field.maxLength());
                addRenderableWidget(box);
                inputs.add(new Input(field, box::getValue, labelY));
                y += LABEL_HEIGHT + MULTI_LINE_HEIGHT + FIELD_GAP;
            } else {
                EditBox box = new EditBox(font, x, y + LABEL_HEIGHT, width, SINGLE_LINE_HEIGHT,
                        Component.literal(field.label()));
                box.setMaxLength(field.maxLength());
                addRenderableWidget(box);
                inputs.add(new Input(field, box::getValue, labelY));
                y += LABEL_HEIGHT + SINGLE_LINE_HEIGHT + FIELD_GAP;
            }
        }
        int footerY = topPos + imageHeight - FOOTER_HEIGHT;
        addRenderableWidget(Button.builder(Component.translatable("straja.form.submit"), b -> submit())
                .bounds(x, footerY, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("straja.form.cancel"), b -> onClose())
                .bounds(x + 88, footerY, 80, 20).build());
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
                0xF0_000000 | GuiTheme.COLOR_NIGHT);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight,
                0xFF_000000 | GuiTheme.COLOR_LEATHER);
        graphics.fill(leftPos + GuiTheme.MARGIN, topPos + GuiTheme.RULE_Y,
                leftPos + imageWidth - GuiTheme.MARGIN, topPos + GuiTheme.RULE_Y + 1,
                0xFF_000000 | GuiTheme.COLOR_LEATHER);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!headerIcon.isEmpty()) {
            graphics.renderItem(headerIcon, GuiTheme.MARGIN, GuiTheme.HEADER_Y - 1);
        }
        graphics.drawString(font, title, GuiTheme.TITLE_X, GuiTheme.HEADER_Y + 2,
                0xFF_000000 | GuiTheme.COLOR_PAPER_BRIGHT, false);
        graphics.drawWordWrap(font, StrajaFormMenu.textComponent(getMenu().view().prompt()),
                GuiTheme.MARGIN, GuiTheme.BODY_Y, imageWidth - GuiTheme.MARGIN * 2,
                0xFF_000000 | GuiTheme.COLOR_PAPER_DIM);
        for (Input input : inputs) {
            graphics.drawString(font, input.field().label(), GuiTheme.MARGIN, input.labelY(),
                    0xFF_000000 | GuiTheme.COLOR_PAPER_DIM, false);
        }
    }
}
