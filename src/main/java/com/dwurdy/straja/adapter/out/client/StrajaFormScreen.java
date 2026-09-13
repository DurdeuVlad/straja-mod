package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.adapter.in.form.FormPayloads;
import com.dwurdy.straja.adapter.in.form.StrajaFormMenu;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Native client form: renders the server-provided view, sends payloads only. */
public class StrajaFormScreen extends AbstractContainerScreen<StrajaFormMenu> {
    private static final int SINGLE_LINE_HEIGHT = 20;
    private static final int MULTI_LINE_HEIGHT = 56;

    private final List<Input> inputs = new ArrayList<>();
    private boolean submitted;
    private boolean cancelSent;

    private record Input(Field field, Supplier<String> value) {}

    public StrajaFormScreen(StrajaFormMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 240;
        int fieldsHeight = 0;
        for (Field field : menu.view().fields()) {
            fieldsHeight += (field.multiline() ? MULTI_LINE_HEIGHT : SINGLE_LINE_HEIGHT) + 8;
        }
        this.imageHeight = 62 + fieldsHeight + 36;
    }

    @Override
    protected void init() {
        super.init();
        inputs.clear();
        int x = leftPos + 12;
        int y = topPos + 58;
        int width = imageWidth - 24;
        for (Field field : getMenu().view().fields()) {
            if (field.multiline()) {
                MultiLineEditBox box = new MultiLineEditBox(font, x, y, width,
                        MULTI_LINE_HEIGHT, Component.literal(field.label()),
                        Component.literal(field.label()));
                box.setCharacterLimit(field.maxLength());
                addRenderableWidget(box);
                inputs.add(new Input(field, box::getValue));
                y += MULTI_LINE_HEIGHT + 8;
            } else {
                EditBox box = new EditBox(font, x, y, width, SINGLE_LINE_HEIGHT,
                        Component.literal(field.label()));
                box.setMaxLength(field.maxLength());
                box.setHint(Component.literal(field.label()));
                addRenderableWidget(box);
                inputs.add(new Input(field, box::getValue));
                y += SINGLE_LINE_HEIGHT + 8;
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Submit"), b -> submit())
                .bounds(x, topPos + imageHeight - 28, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x + 88, topPos + imageHeight - 28, 80, 20).build());
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
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xC0101015);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFF8A8A8A);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 12, 8, 0xFFFFFFFF, false);
        graphics.drawWordWrap(font, FormattedText.of(getMenu().view().prompt()),
                12, 22, imageWidth - 24, 0xFF9E9E9E);
    }
}
