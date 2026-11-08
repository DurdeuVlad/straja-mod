package com.dwurdy.straja.adapter.in.menu;

import java.util.List;

/**
 * A rendered desk page: title, chest row count and the buttons carrying
 * explicit slot indices inside the desk page.
 */
public record DeskPage(String title, int rows, List<DeskButton> buttons) {
    public DeskPage {
        title = title == null ? "" : title;
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    /** The button rendered at {@code slot}, or null for an unused slot. */
    public DeskButton buttonAt(int slot) {
        for (DeskButton button : buttons) {
            if (button.slot() == slot) return button;
        }
        return null;
    }
}
