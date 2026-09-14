package com.dwurdy.straja.adapter.in.form;

import com.dwurdy.straja.application.port.in.FormSessionUseCase.Field;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.View;
import com.dwurdy.straja.bootstrap.StrajaMenus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Slotless menu carrying a server-issued form view. All state lives in the
 * session behind the inbound port; the menu only transports the view.
 */
public class StrajaFormMenu extends AbstractContainerMenu {
    private static final int MAX_SESSION_ID = 128;
    private static final int MAX_TITLE = 80;
    private static final int MAX_PROMPT = 512;
    private static final int MAX_FIELDS = 4;
    private static final int MAX_FIELD_ID = 32;
    private static final int MAX_LABEL = 80;
    private static final int MAX_FIELD_LENGTH = 2_000;

    private final View view;

    public StrajaFormMenu(int containerId, Inventory playerInventory, View view) {
        super(StrajaMenus.FORM.get(), containerId);
        this.view = view;
    }

    /** Client-side factory for {@code IMenuTypeExtension}; decodes the open data. */
    public static StrajaFormMenu client(int containerId, Inventory playerInventory,
                                        RegistryFriendlyByteBuf data) {
        return new StrajaFormMenu(containerId, playerInventory, readView(data));
    }

    public View view() {
        return view;
    }

    /** Resolves the compact server-to-client marker used for localized form text. */
    public static Component textComponent(String value) {
        if (value != null && value.startsWith("@")) {
            return Component.translatable(value.substring(1));
        }
        return Component.literal(value == null ? "" : value);
    }

    public static void writeView(RegistryFriendlyByteBuf buf, View view) {
        buf.writeUtf(view.sessionId(), MAX_SESSION_ID);
        buf.writeUtf(view.title(), MAX_TITLE);
        buf.writeUtf(view.prompt(), MAX_PROMPT);
        buf.writeVarInt(view.fields().size());
        for (Field field : view.fields()) {
            buf.writeUtf(field.id(), MAX_FIELD_ID);
            buf.writeUtf(field.label(), MAX_LABEL);
            buf.writeVarInt(field.maxLength());
            buf.writeBoolean(field.multiline());
        }
    }

    private static View readView(RegistryFriendlyByteBuf buf) {
        String sessionId = buf.readUtf(MAX_SESSION_ID);
        String title = buf.readUtf(MAX_TITLE);
        String prompt = buf.readUtf(MAX_PROMPT);
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_FIELDS) {
            throw new IllegalArgumentException("form field count out of bounds: " + count);
        }
        List<Field> fields = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Field field = new Field(buf.readUtf(MAX_FIELD_ID), buf.readUtf(MAX_LABEL),
                    buf.readVarInt(), buf.readBoolean());
            if (field.maxLength() < 1 || field.maxLength() > MAX_FIELD_LENGTH) {
                throw new IllegalArgumentException("form field length out of bounds");
            }
            fields.add(field);
        }
        if (fields.stream().map(Field::id).distinct().count() != fields.size()) {
            throw new IllegalArgumentException("duplicate form field id");
        }
        return new View(sessionId, title, prompt, fields);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide) {
            FormSessionBridge.cancelSession(player.getUUID(), view.sessionId());
        }
    }
}
