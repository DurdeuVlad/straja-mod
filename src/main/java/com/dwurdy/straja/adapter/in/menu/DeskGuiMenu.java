package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.bootstrap.StrajaMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * The desk GUI container — a slotless custom {@code MenuType}
 * ({@code straja:desk}) that transports a rendered {@link DeskPage} to the
 * client screen. All authority stays server-side: the wire view carries
 * titles/icons/lore but never action ids; clicks come back as the
 * {@code desk_click} payload carrying only the widget slot, which the session
 * resolves against the page it rendered. Nothing in this menu can move items.
 */
public class DeskGuiMenu extends AbstractContainerMenu {
    private final DeskMenuSession session;   // server side
    private final String sessionId;          // server side
    private DeskWire.View view;              // client side

    /** Server: the menu owns the session's lifecycle. */
    DeskGuiMenu(int containerId, Inventory inventory, DeskMenuSession session,
                String sessionId) {
        super(StrajaMenus.DESK.get(), containerId);
        this.session = session;
        this.sessionId = sessionId;
        this.view = null;
    }

    private DeskGuiMenu(int containerId, Inventory inventory, DeskWire.View view) {
        super(StrajaMenus.DESK.get(), containerId);
        this.session = null;
        this.sessionId = null;
        this.view = view;
    }

    /** Client-side factory for {@code IMenuTypeExtension}; decodes the open data. */
    public static DeskGuiMenu client(int containerId, Inventory inventory,
                                     RegistryFriendlyByteBuf data) {
        return new DeskGuiMenu(containerId, inventory, readView(data));
    }

    DeskMenuSession session() { return session; }
    String sessionId() { return sessionId; }

    /** Diagnostic view of the open page — see {@link DeskMenuSession#describeButtons()}. */
    public java.util.List<String> describePageButtons() {
        return session == null ? java.util.List.of() : session.describeButtons();
    }

    /** The view the client renders — null until the open data lands. */
    public DeskWire.View view() { return view; }

    /** Client page turn: a fresh {@code desk_page} payload replaces the view. */
    public void applyView(DeskWire.View next) { this.view = next; }

    /**
     * One client click: the session+sequence must match the page this player
     * last rendered — a stale payload (page in flight, forged session id)
     * drops silently instead of dispatching the wrong action.
     */
    void acceptClick(String payloadSessionId, int seq, int slot) {
        if (session == null || sessionId == null) return;
        if (!sessionId.equals(payloadSessionId) || session.seq() != seq) return;
        session.onClick(slot);
    }

    /** Wire entry point: open-menu extra data and the desk_page codec. */
    public static void writeView(FriendlyByteBuf buf, DeskWire.View view) {
        DeskWire.write(adaptFor(buf), view);
    }

    public static DeskWire.View readView(FriendlyByteBuf buf) {
        return DeskWire.read(adaptFor(buf));
    }

    static DeskWire.Buf adaptFor(FriendlyByteBuf buf) {
        return new DeskWire.Buf() {
            @Override public void writeUtf(String value, int maxLength) {
                buf.writeUtf(value, maxLength);
            }

            @Override public void writeVarInt(int value) {
                buf.writeVarInt(value);
            }

            @Override public String readUtf(int maxLength) {
                return buf.readUtf(maxLength);
            }

            @Override public int readVarInt() {
                return buf.readVarInt();
            }
        };
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * No slots exist, so the vanilla click packet can never address one —
     * swallow unconditionally; real input arrives on the desk_click payload.
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {}

    @Override
    public boolean stillValid(Player player) {
        return session == null || session.isAlive();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide && session != null) {
            session.onRemoved();
        }
    }
}
