package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

/** ServerPlayer → PlayerGateway. All MC API calls are confined here. */
public class MinecraftPlayerGateway implements PlayerGateway {
    private final MinecraftServer server;
    private final UUID uuid;

    public MinecraftPlayerGateway(MinecraftServer server, UUID uuid) {
        this.server = server;
        this.uuid = uuid;
    }

    public ServerPlayer entity() {
        return server.getPlayerList().getPlayer(uuid);
    }

    @Override public UUID uuid() { return uuid; }

    @Override public String name() {
        ServerPlayer player = entity();
        return player != null ? player.getGameProfile().getName() : uuid.toString();
    }

    @Override public boolean isOnline() { return entity() != null; }

    @Override public boolean isOp() {
        ServerPlayer player = entity();
        return player != null && server.getPlayerList().isOp(player.getGameProfile());
    }

    @Override public String dimension() {
        ServerPlayer player = entity();
        return player != null ? player.level().dimension().location().toString() : "";
    }

    @Override public double x() { ServerPlayer p = entity(); return p != null ? p.getX() : 0; }
    @Override public double y() { ServerPlayer p = entity(); return p != null ? p.getY() : 0; }
    @Override public double z() { ServerPlayer p = entity(); return p != null ? p.getZ() : 0; }

    @Override public double health() { ServerPlayer p = entity(); return p != null ? p.getHealth() : 0; }
    @Override public double maxHealth() {
        ServerPlayer p = entity();
        return p != null ? p.getMaxHealth() : 0;
    }
    @Override public double absorption() { ServerPlayer p = entity(); return p != null ? p.getAbsorptionAmount() : 0; }

    @Override public void setHealth(double value) {
        ServerPlayer p = entity();
        if (p != null) p.setHealth((float) value);
    }

    @Override public void tell(String text) {
        ServerPlayer p = entity();
        if (p != null) p.sendSystemMessage(Component.literal(text));
    }

    @Override public void actionbar(String text) {
        ServerPlayer p = entity();
        if (p != null) p.displayClientMessage(Component.literal(text), true);
    }

    @Override public boolean give(ItemSpec item) {
        ServerPlayer p = entity();
        if (p == null) return false;
        return p.getInventory().add(MinecraftInventoryView.build(item));
    }

    @Override public boolean giveVerified(ItemSpec item) {
        ServerPlayer p = entity();
        if (p == null) return false;
        if (!inventory().canReceive(java.util.List.of(item))) return false;
        return p.getInventory().add(MinecraftInventoryView.build(item));
    }

    @Override public InventoryView inventory() {
        ServerPlayer p = entity();
        return p != null ? new MinecraftInventoryView(p.getInventory()) : InventoryView.empty();
    }

    @Override public ItemView mainHand() {
        ServerPlayer p = entity();
        return p != null ? MinecraftInventoryView.view(p.getMainHandItem()) : ItemView.EMPTY;
    }

    @Override public int selectedSlot() {
        ServerPlayer p = entity();
        return p != null ? p.getInventory().selected : -1;
    }

    @Override public void selectSlot(int slot) {
        ServerPlayer p = entity();
        if (p != null && slot >= 0 && slot <= 8) p.getInventory().selected = slot;
    }

    @Override public void applyEffect(String effectId, int durationTicks, int amplifier) {
        ServerPlayer p = entity();
        if (p == null) return;
        var holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(effectId));
        holder.ifPresent(effect -> p.addEffect(new MobEffectInstance(effect, durationTicks, amplifier)));
    }

    @Override public void closeMenu() {
        ServerPlayer p = entity();
        if (p != null) p.closeContainer();
    }

    @Override public void teleport(String dimension, double x, double y, double z) {
        ServerPlayer p = entity();
        if (p == null) return;
        var levelKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse(dimension));
        ServerLevel level = server.getLevel(levelKey);
        if (level != null) {
            p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
        }
    }

    @Override public boolean startRiding(UUID vehicleUuid) {
        ServerPlayer passenger = entity();
        ServerPlayer vehicle = vehicleUuid == null ? null : server.getPlayerList().getPlayer(vehicleUuid);
        return passenger != null && vehicle != null && passenger.startRiding(vehicle, true);
    }

    @Override public void stopRiding() {
        ServerPlayer p = entity();
        if (p != null) p.stopRiding();
    }

    @Override public boolean isPassenger() {
        ServerPlayer p = entity();
        return p != null && p.isPassenger();
    }

    @Override public boolean isPassengerOf(UUID vehicleUuid) {
        ServerPlayer p = entity();
        return p != null && vehicleUuid != null && p.getVehicle() != null
                && vehicleUuid.equals(p.getVehicle().getUUID());
    }

    @Override public boolean hasPassenger(UUID passengerUuid) {
        ServerPlayer p = entity();
        return p != null && passengerUuid != null
                && p.getPassengers().stream().anyMatch(entity -> passengerUuid.equals(entity.getUUID()));
    }
}
