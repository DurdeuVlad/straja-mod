package com.dwurdy.straja.adapter.in.menu;

/**
 * A placed desk-menu button: the grid {@code slot} it renders at, the icon and
 * the action id a click dispatches — either a real NPC action id
 * ({@code "fine-pay:F-3"}) or an internal nav id
 * ({@code desk:back}, {@code desk:prev}, {@code desk:next}, {@code desk:yes},
 * {@code desk:no}, {@code desk:info}).
 */
public record DeskButton(int slot, DeskIcon icon, String actionId) {
    /** Unplaced content item — {@link DeskLayout} assigns the real slot. */
    public DeskButton(DeskIcon icon, String actionId) {
        this(-1, icon, actionId);
    }
}
