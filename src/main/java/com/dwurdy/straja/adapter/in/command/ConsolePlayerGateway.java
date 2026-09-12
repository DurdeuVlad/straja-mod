package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Console/RCON source as a player-like gateway. It is a real authority
 * principal: isOp() is true and messages go to the command source, so the same
 * services can be driven without a client.
 */
public class ConsolePlayerGateway implements PlayerGateway {
    public static final UUID CONSOLE_UUID = new UUID(0L, 0L);

    private final CommandSourceStack source;

    public ConsolePlayerGateway(CommandSourceStack source) {
        this.source = source;
    }

    @Override public UUID uuid() { return CONSOLE_UUID; }
    @Override public String name() { return source.getTextName(); }
    @Override public boolean isOnline() { return true; }
    @Override public boolean isOp() { return true; }
    @Override public String dimension() { return source.getLevel().dimension().location().toString(); }
    @Override public double x() { return source.getPosition().x; }
    @Override public double y() { return source.getPosition().y; }
    @Override public double z() { return source.getPosition().z; }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public double absorption() { return 0; }
    @Override public void setHealth(double value) {}
    @Override public void tell(String text) { source.sendSystemMessage(Component.literal(text)); }
    @Override public boolean give(ItemSpec item) { return false; }
    @Override public boolean giveVerified(ItemSpec item) { return false; }

    @Override public InventoryView inventory() {
        return new InventoryView() {
            @Override public int slots() { return 0; }
            @Override public ItemView stackAt(int slot) { return ItemView.EMPTY; }
            @Override public ItemView extract(int slot, int amount) { return ItemView.EMPTY; }
            @Override public boolean canReceive(List<ItemSpec> items) { return false; }
        };
    }

    @Override public ItemView mainHand() { return ItemView.EMPTY; }
    @Override public int selectedSlot() { return -1; }
    @Override public void selectSlot(int slot) {}
    @Override public void applyEffect(String effectId, int durationTicks, int amplifier) {}
    @Override public void closeMenu() {}
    @Override public void teleport(String dimension, double x, double y, double z) {}
}
