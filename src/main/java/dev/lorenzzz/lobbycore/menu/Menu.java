package dev.lorenzzz.lobbycore.menu;

import dev.lorenzzz.lobbycore.C;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu definition loaded from one file under {@code menus/}. The file name is the menu id, so
 * {@code menus/serverselector.yml} is opened with {@code [MENU] serverselector}.
 *
 * <p>Instances are immutable and shared by every viewer; the per-player part lives in
 * {@link MenuHolder}.
 */
public final class Menu {

    private final String id;
    private final String title;
    private final int size;
    private final boolean refreshEnabled;
    private final long refreshRate;

    /** Slot -> item. Fillers are resolved into concrete slots here, once, not on every open. */
    private final Map<Integer, MenuItem> layout = new LinkedHashMap<>();
    private final List<MenuItem> fillers = new ArrayList<>();

    private Menu(String id, YamlConfiguration config) {
        this.id = id;
        this.title = config.getString("title", id);
        // Chest inventories come in rows of nine and cap at six; anything else throws on create.
        int configured = config.getInt("slots", 27);
        this.size = Math.max(9, Math.min(54, ((configured + 8) / 9) * 9));
        this.refreshEnabled = config.getBoolean("refresh.enabled", false);
        this.refreshRate = Math.max(1L, config.getLong("refresh.rate", 40L));

        ConfigurationSection items = config.getConfigurationSection("items");
        if (items == null) return;

        for (String key : items.getKeys(false)) {
            ConfigurationSection itemSection = items.getConfigurationSection(key);
            if (itemSection == null) continue;
            MenuItem item = new MenuItem(itemSection);

            if (item.isFiller()) {
                fillers.add(item);
                continue;
            }
            for (int slot : item.slots()) {
                if (slot >= 0 && slot < size) layout.put(slot, item);
            }
        }
    }

    public static Menu load(String id, YamlConfiguration config) {
        return new Menu(id, config);
    }

    public String id() { return id; }
    public String title() { return title; }
    public int size() { return size; }
    public boolean refreshEnabled() { return refreshEnabled; }
    public long refreshRate() { return refreshRate; }

    /** The item at a slot, or null — including for filler slots, which are not clickable. */
    public MenuItem itemAt(int slot) {
        return layout.get(slot);
    }

    /** Draws the whole inventory for one viewer. Also used by the refresh task. */
    public void render(Player viewer, Inventory inventory) {
        inventory.clear();

        for (Map.Entry<Integer, MenuItem> entry : layout.entrySet()) {
            MenuItem item = entry.getValue();
            if (!item.visibleTo(viewer)) continue;
            inventory.setItem(entry.getKey(), item.render(viewer));
        }

        if (fillers.isEmpty()) return;
        // Fillers are static by definition, so the stack is built once and cloned into the gaps.
        for (MenuItem filler : fillers) {
            if (!filler.visibleTo(viewer)) continue;
            ItemStack stack = filler.render(viewer);
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (inventory.getItem(slot) == null) inventory.setItem(slot, stack.clone());
            }
        }
    }

    /** The title as this viewer sees it — server selectors put counts in theirs. */
    public net.kyori.adventure.text.Component renderTitle(Player viewer) {
        return C.parse(title, viewer, Map.of("%player%", viewer.getName()));
    }
}
