package dev.lorenzzz.lobbycore.module.modules.world;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.block.Action;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Everything that has to <em>not</em> happen in a lobby: no block breaking, no fall damage, no
 * hunger, no mobs, no weather.
 *
 * <p>Each switch is read once into a field rather than out of the config on every event. These
 * handlers sit on the hottest paths the server has — {@code EntityDamageEvent} fires for every
 * entity taking damage anywhere — and a YAML map lookup per call is the kind of cost that only
 * shows up once the lobby is full.
 */
public final class WorldSettingsModule extends AbstractModule implements Listener {

    private static final String BYPASS = "lobbycore.bypass.world";

    private boolean godMode;
    private boolean disableHunger;
    private boolean disableFallDamage;
    private boolean disablePvp;
    private boolean disableVoidDeath;
    private boolean disableFireDamage;
    private boolean disableDrowning;
    private boolean disableOffHandSwap;

    private boolean disableWeather;
    private boolean disableDeathMessage;
    private boolean disableMobSpawning;

    private boolean disableItemDrop;
    private boolean disableItemPickup;

    private boolean disableBlockBreak;
    private boolean disableBlockPlace;
    private boolean disableBlockInteract;
    private boolean disableBlockBurn;
    private boolean disableFireSpread;
    private boolean disableLeafDecay;

    private final Set<Material> blockedPlace = EnumSet.noneOf(Material.class);

    public WorldSettingsModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "world-settings"; }
    @Override public String configFile() { return "world_settings"; }

    @Override
    public String description() {
        return "Protezione del mondo della lobby";
    }

    @Override
    public void onEnable() {
        var config = config();
        String root = "world_settings.";

        godMode = config.getBoolean(root + "god_mode", true);
        disableHunger = config.getBoolean(root + "disable_hunger_loss", true);
        disableFallDamage = config.getBoolean(root + "disable_fall_damage", true);
        disablePvp = config.getBoolean(root + "disable_player_pvp", true);
        disableVoidDeath = config.getBoolean(root + "disable_void_death", true);
        disableFireDamage = config.getBoolean(root + "disable_fire_damage", true);
        disableDrowning = config.getBoolean(root + "disable_drowning", true);
        disableOffHandSwap = config.getBoolean(root + "disable_off_hand_swap", true);

        disableWeather = config.getBoolean(root + "disable_weather_change", true);
        disableDeathMessage = config.getBoolean(root + "disable_death_message", true);
        disableMobSpawning = config.getBoolean(root + "disable_mob_spawning", true);

        disableItemDrop = config.getBoolean(root + "disable_item_drop", true);
        disableItemPickup = config.getBoolean(root + "disable_item_pickup", true);

        disableBlockBreak = config.getBoolean(root + "disable_block_break", true);
        disableBlockPlace = config.getBoolean(root + "disable_block_place", true);
        disableBlockInteract = config.getBoolean(root + "disable_block_interact", true);
        disableBlockBurn = config.getBoolean(root + "disable_block_burn", true);
        disableFireSpread = config.getBoolean(root + "disable_block_fire_spread", true);
        disableLeafDecay = config.getBoolean(root + "disable_block_leaf_decay", true);

        blockedPlace.clear();
        for (String name : config.getStringList(root + "blocked_place")) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material != null) blockedPlace.add(material);
        }

        listen(this);
    }

    /** Staff with the bypass permission play by normal rules, for building and moderation. */
    private boolean guarded(Player player) {
        return inLobbyWorld(player.getWorld()) && !player.hasPermission(BYPASS);
    }

    // ------------------------------------------------------------------ player

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!disableHunger) return;
        if (event.getEntity() instanceof Player player && guarded(player)) {
            event.setCancelled(true);
            // Setting the level too: cancelling alone leaves whatever the client predicted on
            // screen until the next update, which reads as a flickering hunger bar.
            event.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !inLobbyWorld(player.getWorld())) return;

        // In lobby non si muore, punto — vale per tutti, anche con il bypass (che serve
        // per costruire, non per morire). Il PvP consensuale non si rompe: il modulo
        // hub-pvp gira a HIGHEST e riabilita il danno solo fra due giocatori attivi.
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            if (!disableVoidDeath) return;
            event.setCancelled(true);
            // Il vuoto e l'unico danno che richiede di piu di un cancel: il giocatore sta
            // ancora cadendo e ci rientrerebbe al tick dopo.
            var spawn = managers.modules()
                    .module("spawn", dev.lorenzzz.lobbycore.module.modules.spawn.SpawnModule.class);
            player.teleportAsync(spawn != null && spawn.spawn() != null
                    ? spawn.spawn()
                    : player.getWorld().getSpawnLocation());
            return;
        }
        if (godMode) {
            event.setCancelled(true);
            return;
        }
        if (!guarded(player)) return;

        switch (event.getCause()) {
            case FALL -> { if (disableFallDamage) event.setCancelled(true); }
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> { if (disableFireDamage) event.setCancelled(true); }
            case DROWNING -> { if (disableDrowning) event.setCancelled(true); }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!disablePvp) return;
        if (!(event.getEntity() instanceof Player victim) || !inLobbyWorld(victim.getWorld())) return;
        if (!(event.getDamager() instanceof Player)) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (disableDeathMessage && inLobbyWorld(event.getEntity().getWorld())) {
            event.deathMessage(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (disableOffHandSwap && guarded(event.getPlayer())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------- items

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (disableItemDrop && guarded(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!disableItemPickup) return;
        if (event.getEntity() instanceof Player player && guarded(player)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (disableBlockBreak && guarded(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!guarded(event.getPlayer())) return;
        // The blocked list applies even when placing is generally allowed — that is how the
        // lobby-blocks module can hand out a placeable block while bells stay off limits.
        if (disableBlockPlace || blockedPlace.contains(event.getBlockPlaced().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!disableBlockInteract) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.PHYSICAL) return;
        if (event.getClickedBlock() == null || !guarded(event.getPlayer())) return;
        // Only the block half is cancelled: cancelling the whole event would also kill the
        // right-click that opens a hotbar menu.
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (disableBlockBurn && inLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!disableFireSpread) return;
        if (event.getSource().getType() == Material.FIRE && inLobbyWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent event) {
        if (disableLeafDecay && inLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    // -------------------------------------------------------------------- misc

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (disableWeather && event.toWeatherState() && inLobbyWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (!disableMobSpawning) return;
        if (event.getEntity() instanceof Player) return;
        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity)) return;
        if (inLobbyWorld(event.getEntity().getWorld())) event.setCancelled(true);
    }
}
