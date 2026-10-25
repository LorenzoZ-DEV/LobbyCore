package dev.lorenzzz.lobbycore.module.modules.hotbar;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.menu.MenuManager;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compass, the clock and the rest of the lobby hotbar: what each slot holds and what
 * right-clicking it runs.
 *
 * <p>Items are identified by a persistent-data tag holding the item's config key, never by
 * display name or material. Name matching is what made the old separate plugins step on each
 * other — two of them handing out a compass meant whichever listener ran first claimed the
 * click — and it breaks the moment an admin edits a name or a player renames something at an
 * anvil. The tag is written by us, invisible, and survives both.
 */
public final class HotbarModule extends AbstractModule implements Listener {

    /** Slot in the hotbar -> the item that owns it. Ordered, so give-out is deterministic. */
    private final Map<Integer, HotbarItem> bySlot = new LinkedHashMap<>();

    private NamespacedKey key;
    private boolean lockInventory;
    private boolean giveOnJoin;
    private boolean clearBefore;
    private long joinDelayTicks;

    /** One configured item: where it goes, what it looks like, what it does. */
    private record HotbarItem(String id, int slot, ItemStack stack, List<String> actions,
                              String permission) {

        boolean allowed(Player player) {
            return permission.isEmpty() || player.hasPermission(permission);
        }
    }

    public HotbarModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "hotbar"; }
    @Override public String configFile() { return "custom_join_items"; }

    @Override
    public String description() {
        return "Item della hotbar (selector, informazioni, ...)";
    }

    @Override
    public void onEnable() {
        key = new NamespacedKey(plugin, "hotbar_item");
        var config = config();
        String root = "custom_join_items.";

        lockInventory = config.getBoolean(root + "disable_inventory_movement", true);
        giveOnJoin = config.getBoolean(root + "give_on_join", true);
        clearBefore = config.getBoolean(root + "clear_inventory_before", false);
        joinDelayTicks = config.getLong(root + "give_delay_ticks", 2L);

        load(config.getConfigurationSection(root + "items"));
        warnSlotConflicts();

        listen(this);
        for (Player player : plugin.getServer().getOnlinePlayers()) give(player);
    }

    @Override
    protected void shutdown() {
        // Take the items back: leaving them in the inventory of a player who is still online
        // would leave dead buttons that no listener answers any more.
        for (Player player : plugin.getServer().getOnlinePlayers()) remove(player);
        bySlot.clear();
    }

    /**
     * Avvisa quando uno degli item configurati vuole lo slot che il player-hider usa per il suo
     * toggle. Non si sposta niente da soli — la scelta e dell'admin — ma il conflitto va detto,
     * invece di far sparire un item in silenzio al prossimo join.
     */
    private void warnSlotConflicts() {
        PlayerHiderModule hider = managers.modules().module("player-hider", PlayerHiderModule.class);
        if (hider == null) return;
        HotbarItem clash = bySlot.get(hider.slot());
        if (clash != null) {
            C.warning("L'item hotbar '" + clash.id() + "' e il toggle del modulo 'player-hider'"
                    + " vogliono entrambi lo slot " + hider.slot()
                    + " — uno dei due sparira. Cambia slot in uno dei due file.");
        }
    }

    private void load(ConfigurationSection items) {
        bySlot.clear();
        if (items == null) return;

        for (String id : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(id);
            if (section == null) continue;

            int slot = section.getInt("slot", -1);
            if (slot < 0 || slot > 8) {
                C.warning("L'item hotbar '" + id + "' ha slot " + slot + " (ammessi 0-8) — saltato.");
                continue;
            }

            ItemStack stack = ItemBuilder.fromConfig(section);
            var meta = stack.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
                stack.setItemMeta(meta);
            }

            HotbarItem previous = bySlot.put(slot, new HotbarItem(id, slot, stack,
                    section.getStringList("actions"), section.getString("permission", "")));
            if (previous != null) {
                C.warning("Gli item hotbar '" + previous.id() + "' e '" + id
                        + "' vogliono entrambi lo slot " + slot + " — vince il secondo.");
            }
        }
    }

    /** Puts every item this player may have into its slot. */
    public void give(Player player) {
        if (!giveOnJoin || !inLobbyWorld(player.getWorld())) return;
        if (clearBefore) player.getInventory().clear();

        for (HotbarItem item : bySlot.values()) {
            if (item.allowed(player)) {
                player.getInventory().setItem(item.slot(), item.stack().clone());
            }
        }
    }

    /** Removes every item of ours from the player's inventory, wherever it ended up. */
    public void remove(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (idOf(contents[slot]) != null) player.getInventory().setItem(slot, null);
        }
    }

    /** The config key stamped on an item, or {@code null} when it is not one of ours. */
    private String idOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** True when the stack is a hotbar item — used by the other modules' drop guards. */
    public boolean isHotbarItem(ItemStack item) {
        return idOf(item) != null;
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // One tick late on purpose: another plugin restoring an inventory on join would
        // otherwise overwrite what we just placed.
        if (joinDelayTicks <= 0) {
            give(event.getPlayer());
            return;
        }
        later(() -> {
            if (event.getPlayer().isOnline()) give(event.getPlayer());
        }, joinDelayTicks);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        // LOWEST e senza ignoreCancelled di proposito. Gli item sono marchiati con un tag
        // solo nostro, quindi possiamo reclamarli prima di chiunque altro senza disturbare
        // gli item altrui: plugin come WorldEdit/FAWE (la bussola e la sua navigation wand
        // di default) o le protezioni cancellano il click a priorita piu alte, e con HIGH +
        // ignoreCancelled il menu non si apriva mai — a parte guardando un blocco vicino,
        // l'unico caso che FAWE lasciava passare.
        // L'evento scatta una volta per mano: senza questo controllo ogni click destro
        // eseguirebbe le azioni due volte (doppio [MENU], doppio suono, ...).
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        String id = idOf(event.getItem());
        if (id == null) return;

        // Stop the vanilla use as well — a right-clicked clock or compass would otherwise also
        // do whatever the item normally does. Vale anche per gli altri plugin: il click era
        // diretto a noi, quindi l'evento viene consumato qui.
        event.setCancelled(true);

        HotbarItem item = byId(id);
        if (item == null || !item.allowed(event.getPlayer())) return;

        managers.activity().record(ActivityLog.HOTBAR, event.getPlayer(), id);
        managers.actions().execute(event.getPlayer(), item.actions());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // Gli item venivano dati solo al join: chi rientrava in lobby da un altro mondo
        // restava senza selector, e chi ne usciva se li portava dietro.
        Player player = event.getPlayer();
        if (inLobbyWorld(player.getWorld())) {
            give(player);
        } else {
            remove(player);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Dopo la morte l'inventario e vuoto: un tick dopo il respawn per non farlo
        // sovrascrivere dalla gestione del server.
        later(() -> {
            Player player = event.getPlayer();
            if (player.isOnline() && inLobbyWorld(player.getWorld())) give(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (lockInventory && idOf(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!lockInventory) return;
        if (idOf(event.getMainHandItem()) != null || idOf(event.getOffHandItem()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!lockInventory) return;
        // A click inside one of our menus is already cancelled by the menu manager; letting this
        // handler run there too would also block the menu's own buttons.
        if (MenuManager.isMenu(event.getWhoClicked())) return;

        if (idOf(event.getCurrentItem()) != null || idOf(event.getCursor()) != null) {
            event.setCancelled(true);
            return;
        }
        // Number-key swaps move the hotbar slot without the item ever being "current".
        if (event.getHotbarButton() >= 0) {
            ItemStack target = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
            if (idOf(target) != null) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (lockInventory && idOf(event.getOldCursor()) != null) event.setCancelled(true);
    }

    private HotbarItem byId(String id) {
        for (HotbarItem item : bySlot.values()) {
            if (item.id().equals(id)) return item;
        }
        return null;
    }

    /** The slots this module claims, so other modules can avoid fighting over them. */
    public List<Integer> claimedSlots() {
        return new ArrayList<>(bySlot.keySet());
    }

    /** Placeholder values this module exposes, read by the PAPI expansion. */
    public Map<String, String> placeholders() {
        return Map.of("hotbar_items", String.valueOf(bySlot.size()));
    }
}
