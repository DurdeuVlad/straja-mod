package com.dwurdy.straja.adapter.in.test;

import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ItemSpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Headless player used by {@code /straja test *}: a synthetic UUID, virtual
 * inventory, mutable position/health, and a captured message log. Lets every
 * application service be exercised from the server console or RCON without a
 * connected client.
 */
public final class VirtualPlayerGateway implements PlayerGateway {
    private final UUID uuid;
    private final String name;
    private final VirtualInventory inventory = new VirtualInventory();
    private final List<String> messageLog = new ArrayList<>();
    private final Map<String, int[]> effects = new LinkedHashMap<>();

    private String dimension = "minecraft:overworld";
    private double x, y, z;
    private double health = 20;
    private double maxHealth = 20;
    private double absorption;
    private int selectedSlot = -1;
    private boolean op;
    private String dimensionOverride;

    public VirtualPlayerGateway(String name) {
        this.name = name;
        this.uuid = UUID.nameUUIDFromBytes(("straja-test-player:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override public UUID uuid() { return uuid; }
    @Override public String name() { return name; }
    @Override public boolean isOnline() { return true; }
    @Override public boolean isOp() { return op; }
    public void setOp(boolean value) { this.op = value; }

    @Override public String dimension() { return dimensionOverride != null ? dimensionOverride : dimension; }
    public void setDimension(String value) { this.dimensionOverride = value; }
    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }

    public void moveTo(double nx, double ny, double nz) {
        this.x = nx; this.y = ny; this.z = nz;
    }

    @Override public double health() { return health; }
    @Override public double maxHealth() { return maxHealth; }
    @Override public double absorption() { return absorption; }
    @Override public void setHealth(double value) { this.health = Math.max(0, Math.min(value, maxHealth + absorption)); }
    public void setAbsorption(double value) { this.absorption = value; }

    @Override public void tell(String text) { messageLog.add(text); }
    public List<String> messageLog() { return List.copyOf(messageLog); }
    public void clearLog() { messageLog.clear(); }

    @Override public boolean give(ItemSpec item) {
        return inventory.insert(item) == 0;
    }

    @Override public boolean giveVerified(ItemSpec item) {
        if (!inventory.canReceive(List.of(item))) return false;
        return inventory.insert(item) == 0;
    }

    @Override public InventoryView inventory() { return inventory; }
    public VirtualInventory virtualInventory() { return inventory; }

    @Override public ItemView mainHand() {
        return selectedSlot >= 0 ? inventory.stackAt(selectedSlot) : ItemView.EMPTY;
    }

    @Override public int selectedSlot() { return selectedSlot; }
    @Override public void selectSlot(int slot) { this.selectedSlot = slot; }

    @Override public void applyEffect(String effectId, int durationTicks, int amplifier) {
        effects.put(effectId, new int[]{durationTicks, amplifier});
    }
    public Map<String, int[]> effects() { return Map.copyOf(effects); }

    @Override public void closeMenu() {}

    @Override public void teleport(String dim, double nx, double ny, double nz) {
        this.dimension = dim;
        moveTo(nx, ny, nz);
    }
}
