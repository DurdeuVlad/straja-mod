package com.dwurdy.straja.adapter.in.menu;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * The desk chest menu — a vanilla {@code MenuType.GENERIC_9x*} surface that
 * renders on any client as an ordinary chest screen. All slots are display
 * only: {@link #clicked} routes into the session and swallows the click so no
 * item ever moves, shift-clicks are empty and validity dies with the session.
 */
final class DeskMenu extends ChestMenu {
    private final DeskMenuSession session;
    private final SimpleContainer grid;

    DeskMenu(int containerId, Inventory playerInventory, DeskMenuSession session, int rows) {
        super(menuType(clampRows(rows)), containerId, playerInventory,
                new SimpleContainer(clampRows(rows) * DeskLayout.COLUMNS), clampRows(rows));
        this.session = session;
        this.grid = (SimpleContainer) getContainer();
    }

    private static int clampRows(int rows) {
        return Math.min(DeskLayout.ROWS, Math.max(1, rows));
    }

    private static MenuType<ChestMenu> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /** The display grid this menu renders into. */
    SimpleContainer grid() {
        return grid;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Full swallow: clicks dispatch actions, they never move items — the
        // player's own inventory rows stay frozen while the desk is open.
        // broadcastChanges re-syncs so the client's predicted pickup reverts.
        if (slotId >= 0 && slotId < grid.getContainerSize()) {
            session.onClick(slotId, button, clickType);
        }
        if (session.isAlive()) {
            broadcastChanges();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return session.isAlive();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        session.onRemoved();
    }
}
