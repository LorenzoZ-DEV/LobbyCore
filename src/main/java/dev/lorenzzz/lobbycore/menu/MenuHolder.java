package dev.lorenzzz.lobbycore.menu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Ties an open inventory back to the {@link Menu} that produced it.
 *
 * <p>Using {@link InventoryHolder} rather than matching on the inventory title is the whole
 * point: titles are player-visible text that can repeat, be translated, or be spoofed by another
 * plugin opening a chest with the same name, and a click handler that trusts one will happily
 * fire a menu action inside somebody's real chest. The holder is an object identity the server
 * hands back to us, so there is nothing to guess.
 */
public final class MenuHolder implements InventoryHolder {

    private final Menu menu;
    private final Player viewer;
    private final Inventory inventory;

    public MenuHolder(Menu menu, Player viewer) {
        this.menu = menu;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, menu.size(), menu.renderTitle(viewer));
        menu.render(viewer, inventory);
    }

    public Menu menu() {
        return menu;
    }

    public Player viewer() {
        return viewer;
    }

    /** Redraws in place, so an open menu picks up new placeholder values without reopening. */
    public void refresh() {
        menu.render(viewer, inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
