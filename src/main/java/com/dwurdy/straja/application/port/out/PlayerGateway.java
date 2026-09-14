package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.UUID;

/**
 * The domain's view of a player. All authority-sensitive operations funnel
 * through here so the same checks apply to commands, NPCs, GUI and tests.
 */
public interface PlayerGateway {
    UUID uuid();

    String name();

    boolean isOnline();

    boolean isOp();

    String dimension();

    double x();

    double y();

    double z();

    double health();

    double maxHealth();

    double absorption();

    void setHealth(double value);

    void tell(String text);

    /** Sends a client-localized message when the concrete adapter supports it. */
    default void tellKey(String translationKey) { tell(translationKey); }

    /** Replaces the player's action-bar status without adding chat history. */
    default void actionbar(String text) {}

    /** Give an item without capacity guarantees; returns false when refused. */
    boolean give(ItemSpec item);

    /**
     * Give with a capacity pre-check. Returns false and gives nothing when the
     * full batch does not fit. This is the delivery boundary: callers must
     * persist a failure state instead of assuming success.
     */
    boolean giveVerified(ItemSpec item);

    InventoryView inventory();

    ItemView mainHand();

    int selectedSlot();

    void selectSlot(int slot);

    void applyEffect(String effectId, int durationTicks, int amplifier);

    void closeMenu();

    void teleport(String dimension, double x, double y, double z);

    /** Server-authorized vanilla-style passenger operations for custody carry. */
    default boolean startRiding(UUID vehicleUuid) { return false; }
    default void stopRiding() {}
    default boolean isPassenger() { return false; }
    default boolean isPassengerOf(UUID vehicleUuid) { return false; }
    default boolean hasPassenger(UUID passengerUuid) { return false; }

    /** Opens a simple button GUI when the client is present; headless-safe. */
    default void openButtonGui(String title, java.util.List<ButtonSpec> buttons) {}

    record ButtonSpec(int slot, ItemSpec icon, String label, String command) {}
}
