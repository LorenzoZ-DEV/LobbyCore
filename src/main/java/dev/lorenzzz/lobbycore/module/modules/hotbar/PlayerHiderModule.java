package dev.lorenzzz.lobbycore.module.modules.hotbar;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.manager.PlayerDataManager;
import dev.lorenzzz.lobbycore.menu.MenuManager;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.ItemBuilder;
import dev.lorenzzz.lobbycore.util.Sounds;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The dye that hides everybody else.
 *
 * <p>The preference is stored per player, so it survives a relog and — on a network using the
 * MySQL backend — follows the player to the other lobbies. That is the reason it goes through
 * {@link PlayerDataManager} instead of a local map: a player who hides everyone, hops to lobby-2
 * and finds the lobby full again has not been given the feature they asked for.
 *
 * <p>Two directions have to be kept in sync and only one of them is obvious: hiding is applied to
 * the player who toggled it, and also re-applied for every player who joins afterwards, for
 * everyone already hiding. Miss the second and the setting quietly decays as the lobby fills.
 */
public final class PlayerHiderModule extends AbstractModule implements Listener {

    private static final String BYPASS = "lobbycore.hider.bypass";

    private NamespacedKey key;
    private int slot;
    private boolean lockInventory;
    private long cooldownMillis;

    private ItemStack shownItem;
    private ItemStack hiddenItem;

    private String toggleSound;

    private final Cache<UUID, Long> cooldowns = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    public PlayerHiderModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "player-hider"; }
    @Override public String configFile() { return "player_hider"; }

    @Override
    public String description() {
        return "Nascondi giocatori (con preferenza salvata)";
    }

    @Override
    public void onEnable() {
        key = new NamespacedKey(plugin, "player_hider");
        var config = config();

        slot = Math.max(0, Math.min(8, config.getInt("player_hider.slot", 8)));
        lockInventory = config.getBoolean("player_hider.disable_inventory_movement", true);
        cooldownMillis = config.getLong("player_hider.cooldown", 4L) * 1000L;
        toggleSound = config.getString("player_hider.sound", "UI_BUTTON_CLICK");

        shownItem = ItemBuilder.tag(
                ItemBuilder.fromConfig(section("player_hider.not_hidden")), key);
        hiddenItem = ItemBuilder.tag(
                ItemBuilder.fromConfig(section("player_hider.hidden")), key);

        listen(this);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            giveItem(player);
            applyFor(player);
        }
    }

    @Override
    protected void shutdown() {
        // Everyone gets to see everyone again: leaving players hidden after the module stops
        // means invisible players nobody can explain.
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            for (Player target : plugin.getServer().getOnlinePlayers()) {
                if (viewer != target) viewer.showPlayer(plugin, target);
            }
            ItemStack current = viewer.getInventory().getItem(slot);
            if (ItemBuilder.hasTag(current, key)) viewer.getInventory().setItem(slot, null);
        }
        cooldowns.invalidateAll();
    }

    private boolean hiding(Player player) {
        PlayerDataManager.LobbyPlayer data = managers.playerData().get(player);
        return data != null && data.playersHidden();
    }

    private void giveItem(Player player) {
        if (!inLobbyWorld(player.getWorld())) return;
        player.getInventory().setItem(slot, (hiding(player) ? hiddenItem : shownItem).clone());
    }

    /** Applies this player's own preference to everyone currently online. */
    private void applyFor(Player viewer) {
        boolean hide = hiding(viewer);
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (target == viewer) continue;
            if (hide && !target.hasPermission(BYPASS)) {
                viewer.hidePlayer(plugin, target);
            } else {
                viewer.showPlayer(plugin, target);
            }
        }
    }

    /** Hides the newcomer from everybody who is already hiding. */
    private void applyToNewcomer(Player joined) {
        if (joined.hasPermission(BYPASS)) return;
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer != joined && hiding(viewer)) viewer.hidePlayer(plugin, joined);
        }
    }

    public void toggle(Player player) {
        // get() puo essere null se il click arriva prima che il preload async abbia finito:
        // invece di ignorarlo in silenzio (sembra "rotto"), si carica in modo sincrono —
        // come nei command path, una lettura bloccante ogni tanto e accettabile.
        PlayerDataManager.LobbyPlayer data = managers.playerData().get(player);
        if (data == null) data = managers.playerData().getOrLoad(player.getUniqueId(), player.getName());
        if (data == null) return;

        Long last = cooldowns.getIfPresent(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < cooldownMillis) {
            long remaining = (cooldownMillis - (now - last) + 999) / 1000;
            managers.messages().send(player, "hider-cooldown",
                    java.util.Map.of("%seconds%", remaining));
            return;
        }
        cooldowns.put(player.getUniqueId(), now);

        data.playersHidden(!data.playersHidden());
        applyFor(player);
        giveItem(player);

        managers.activity().record(ActivityLog.HIDER, player,
                data.playersHidden() ? "nascosti" : "visibili");
        managers.messages().send(player, data.playersHidden() ? "hider-hidden" : "hider-shown");
        Sounds.play(player, toggleSound, 1f, data.playersHidden() ? 0.8f : 1.2f);
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // A tick late, for the same reason the hotbar waits: the player data load finished on
        // the pre-login thread, but another plugin may still be rewriting the inventory.
        later(() -> {
            if (!player.isOnline()) return;
            giveItem(player);
            applyFor(player);
        }, 2L);
        applyToNewcomer(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.invalidate(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (inLobbyWorld(player.getWorld())) {
            giveItem(player);
            applyFor(player);
            return;
        }
        // Fuori dalla lobby il nascondimento non deve valere: si ridanno visibili tutti
        // e si toglie il toggle, che verra ridato al rientro.
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (target != player) player.showPlayer(plugin, target);
        }
        ItemStack current = player.getInventory().getItem(slot);
        if (ItemBuilder.hasTag(current, key)) player.getInventory().setItem(slot, null);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        later(() -> {
            Player player = event.getPlayer();
            if (player.isOnline() && inLobbyWorld(player.getWorld())) {
                giveItem(player);
                applyFor(player);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        // Stesso motivo della hotbar: i nostri item vanno reclamati per primi, prima che
        // altri plugin cancellino l'evento. Il tag e solo nostro, quindi nessun conflitto.
        // Come per la hotbar: l'evento scatta per entrambe le mani, e senza controllo il
        // toggle scatterebbe due volte — il secondo finirebbe subito in cooldown e il
        // giocatore vedrebbe solo il messaggio di attesa.
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!ItemBuilder.hasTag(event.getItem(), key)) return;

        event.setCancelled(true);
        toggle(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (lockInventory && ItemBuilder.hasTag(event.getItemDrop().getItemStack(), key)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!lockInventory || MenuManager.isMenu(event.getWhoClicked())) return;
        if (ItemBuilder.hasTag(event.getCurrentItem(), key)
                || ItemBuilder.hasTag(event.getCursor(), key)) {
            event.setCancelled(true);
        }
    }

    /** The slot this module owns, so the hotbar module can leave it alone. */
    public int slot() {
        return slot;
    }

    /** Whether a stack is the hider toggle — used by the PvP module when it clears inventories. */
    public boolean isToggle(ItemStack item) {
        return item != null && item.getType() != Material.AIR && ItemBuilder.hasTag(item, key);
    }
}
