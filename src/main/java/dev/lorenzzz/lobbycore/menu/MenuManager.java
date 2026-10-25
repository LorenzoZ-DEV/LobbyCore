package dev.lorenzzz.lobbycore.menu;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.manager.Manager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads every file under {@code menus/}, opens them, and owns the single click listener and
 * refresh task they share.
 *
 * <p>One listener for all menus rather than one per menu: the dispatch is a holder check either
 * way, and a listener per open inventory leaks handlers on every reload.
 */
public final class MenuManager implements Manager, Listener {

    private final LobbyCore plugin;
    private final Map<String, Menu> menus = new LinkedHashMap<>();
    private BukkitTask refreshTask;

    public MenuManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "menus"; }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        loadMenus();

        // One shared 1-second tick drives every menu that asked to refresh; each decides whether
        // its own rate has come round. A timer per menu would be a timer per config file.
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, new RefreshTicker(), 20L, 20L);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        closeAll();
        menus.clear();
    }

    @Override
    public void reload() {
        closeAll();
        loadMenus();
    }

    private void loadMenus() {
        menus.clear();
        File directory = new File(plugin.getDataFolder(), "menus");
        if (!directory.isDirectory()) return;

        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            try {
                // Passa dal ConfigManager invece di leggere il file grezzo: come per i
                // modules/, le chiavi aggiunte da un aggiornamento vengono unite al file
                // esistente e salvate, invece di restare solo nel jar. I menu creati
                // dall'admin (senza controparte nel jar) passano invariati.
                YamlConfiguration config =
                        plugin.getManagers().config().load("menus/" + file.getName());
                menus.put(id, Menu.load(id, config));
            } catch (Exception e) {
                C.warning("Menu '" + id + "' non caricato: " + e.getMessage());
            }
        }
        C.info("Caricati &f" + menus.size() + "&7 menu.");
    }

    /** Opens a menu by id. A missing id is logged once, not thrown at the player. */
    public boolean open(Player player, String id) {
        Menu menu = menus.get(id.toLowerCase(Locale.ROOT));
        if (menu == null) {
            C.warning("Menu '" + id + "' inesistente (serve il file menus/" + id + ".yml).");
            return false;
        }
        plugin.getManagers().activity().record(ActivityLog.MENU, player, "aperto " + menu.id());
        player.openInventory(new MenuHolder(menu, player).getInventory());
        return true;
    }

    public boolean exists(String id) {
        return menus.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public List<String> ids() {
        return new ArrayList<>(menus.keySet());
    }

    /** Closes every open menu — on reload, so nobody is left clicking a stale layout. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        }
    }

    // ---------------------------------------------------------------- listener

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        // Cancel first, unconditionally. A menu inventory is a display, and an uncancelled click
        // anywhere in the view — including shift-clicking out of the player's own inventory —
        // can move a real item into it.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        MenuItem item = holder.menu().itemAt(event.getSlot());
        if (item == null || !item.hasActions() || !item.visibleTo(player)) return;

        List<String> actions = item.actionsFor(event.getClick());
        if (actions.isEmpty()) return;

        plugin.getManagers().activity().record(ActivityLog.MENU, player,
                holder.menu().id() + " slot " + event.getSlot());
        plugin.getManagers().actions().execute(player, actions);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * Redraws open menus whose refresh rate has come round.
     *
     * <p>Rates are in ticks in the config but the ticker runs once a second, so a rate finer than
     * 20 ticks still refreshes once a second — which is as often as a placeholder-backed count is
     * worth re-reading anyway.
     */
    private final class RefreshTicker implements Runnable {

        private long elapsedTicks;

        @Override
        public void run() {
            elapsedTicks += 20L;
            for (Player player : Bukkit.getOnlinePlayers()) {
                InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                if (!(holder instanceof MenuHolder menuHolder)) continue;

                Menu menu = menuHolder.menu();
                if (!menu.refreshEnabled()) continue;
                if (elapsedTicks % Math.max(20L, menu.refreshRate()) >= 20L) continue;
                menuHolder.refresh();
            }
        }
    }

    /** True when the entity is looking at one of our menus — used by hotbar protection. */
    public static boolean isMenu(HumanEntity entity) {
        return entity.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder;
    }
}
