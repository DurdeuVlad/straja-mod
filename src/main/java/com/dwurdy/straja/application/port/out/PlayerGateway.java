package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.UUID;

/**
 * The domain's view of a player. All authority-sensitive operations funnel
 * through here so the same checks apply to commands, NPCs, GUI and tests.
 */
public interface PlayerGateway {
    enum BookCopyResult {
        /** The main hand does not contain an item from the book tag. */
        NOT_A_BOOK,
        /** One copy was inserted while the original stack stayed untouched. */
        COPIED,
        /** The player has a book, but no inventory space for its copy. */
        NO_SPACE
    }

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
    default void tellKey(String translationKey, Object... args) { tell(translationKey); }

    /**
     * Sends a refusal that names the concrete remedy, not just the reason.
     * Player-facing denials route through here so a bare reason can never
     * ship: {@code straja.refusal.format} renders "{reason} → {remedy}" and
     * the remedy key always points at the action, NPC or FAQ topic that
     * unblocks the player.
     */
    default void refuse(String reasonKey, String remedyKey, Object... reasonArgs) {
        tell(reasonKey + " -> " + remedyKey);
    }

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

    /**
     * Copies one book from the main hand without consuming the original.
     * Concrete adapters must preserve the complete native item stack data.
     */
    default BookCopyResult copyMainHandBook() { return BookCopyResult.NOT_A_BOOK; }

    int selectedSlot();

    void selectSlot(int slot);

    void applyEffect(String effectId, int durationTicks, int amplifier);

    void closeMenu();

    /** True when the player's native Straja action form is currently open. */
    default boolean isActionFormOpen() { return false; }

    void teleport(String dimension, double x, double y, double z);

    /** Teleport with facing; default keeps the player's current yaw/pitch. */
    default void teleport(String dimension, double x, double y, double z, float yaw, float pitch) {
        teleport(dimension, x, y, z);
    }

    /** Applies a velocity shove (checkpoint pushback). Default is a no-op. */
    default void setVelocity(double vx, double vy, double vz) {}

    /** True while riding a boat or raft-like vehicle (boarding-zone checks). */
    default boolean ridingBoatLike() { return false; }

    /** Server-authorized vanilla-style passenger operations for custody carry. */
    default boolean startRiding(UUID vehicleUuid) { return false; }
    default void stopRiding() {}
    default boolean isPassenger() { return false; }
    default boolean isPassengerOf(UUID vehicleUuid) { return false; }
    default boolean hasPassenger(UUID passengerUuid) { return false; }

    /** Vanilla game mode name ("survival", "creative", ...) — fakes default to survival. */
    default String gameModeName() { return "survival"; }

    /** Sends a title/subtitle pair; the default degrades to the title message. */
    default void title(String titleKey, String subtitleKey, Object... args) {
        tellKey(titleKey, args);
    }

    /** Opens a simple button GUI when the client is present; headless-safe. */
    default void openButtonGui(String title, java.util.List<ButtonSpec> buttons) {}

    record ButtonSpec(int slot, ItemSpec icon, String label, String command) {}
}
