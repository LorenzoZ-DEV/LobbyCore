package dev.lorenzzz.lobbycore.module.modules.pvp;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.manager.PlayerDataManager;
import dev.lorenzzz.lobbycore.menu.MenuManager;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.ItemBuilder;
import dev.lorenzzz.lobbycore.util.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in PvP inside the lobby: hold the sword for a few seconds and you are kitted up and
 * fightable; put it away for a few seconds and everything you had comes back. A fork of
 * a-hubpvp.
 *
 * <p>The design is worth stating because it is not obvious from the outside. There is no arena
 * and no world change — a player in PvP mode is standing in the same lobby as everyone else, and
 * what separates them is that damage between two players is only allowed when <em>both</em> are
 * active. That is what makes the combat tag necessary: without it, losing a fight is one
 * inventory slot away, so a tagged player cannot leave until the tag expires.
 *
 * <p>State is one {@link PvpSession} per active player plus three countdown maps, all ticked by
 * a single one-second task. Kills and deaths go through the shared player store, so they survive
 * a relog and show up in scoreboards.
 */
public final class HubPvpModule extends AbstractModule implements Listener {

    /** The slot the sword occupies once PvP is active — the middle of the hotbar. */
    private static final int ACTIVE_SLOT = 4;

    private NamespacedKey key;

    private ItemStack sword;
    private ItemStack helmet, chestplate, leggings, boots;

    private int swordSlot;
    private int holdSeconds;
    private int releaseSeconds;
    private int combatTagSeconds;

    private boolean giveOnJoin;
    private long giveDelayTicks;

    private boolean deathParticle;
    private Particle deathParticleType;
    private int deathParticleCount;

    private boolean countdownSound;
    private String countdownSoundName;
    private float countdownVolume, countdownPitch;
    private String activateSound, deactivateSound;

    /** Players in PvP mode, holding what was taken from them. */
    private final Map<UUID, PvpSession> active = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> arming = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> disarming = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> combatTag = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tagNotified = new ConcurrentHashMap<>();

    public HubPvpModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "hub-pvp"; }
    @Override public String configFile() { return "hub_pvp"; }

    @Override
    public String description() {
        return "PvP opzionale nella lobby (fork di a-hubpvp)";
    }

    @Override
    public void onEnable() {
        key = new NamespacedKey(plugin, "pvp_sword");
        var config = config();
        String root = "hub_pvp.";

        swordSlot = Math.max(0, Math.min(8, config.getInt(root + "sword.slot", 7)));
        holdSeconds = Math.max(1, config.getInt(root + "sword.hold_time_seconds", 5));
        releaseSeconds = Math.max(1, config.getInt(root + "sword.deactivate_time_seconds", 5));
        combatTagSeconds = Math.max(0, config.getInt(root + "sword.combat_tag_seconds", 10));

        giveOnJoin = config.getBoolean(root + "give_on_join", true);
        giveDelayTicks = config.getLong(root + "give_on_join_delay", 20L);

        deathParticle = config.getBoolean(root + "death_particle.enabled", true);
        deathParticleCount = config.getInt(root + "death_particle.count", 30);
        try {
            deathParticleType = Particle.valueOf(
                    config.getString(root + "death_particle.type", "EXPLOSION").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            deathParticleType = Particle.EXPLOSION;
        }

        countdownSound = config.getBoolean(root + "sounds.countdown.enabled", true);
        countdownSoundName = config.getString(root + "sounds.countdown.type", "BLOCK_NOTE_BLOCK_PLING");
        countdownVolume = (float) config.getDouble(root + "sounds.countdown.volume", 1.0);
        countdownPitch = (float) config.getDouble(root + "sounds.countdown.pitch", 1.5);
        activateSound = config.getString(root + "sounds.activated.type", "ENTITY_ENDER_DRAGON_GROWL");
        deactivateSound = config.getString(root + "sounds.deactivated.type", "ENTITY_EXPERIENCE_ORB_PICKUP");

        sword = ItemBuilder.tag(ItemBuilder.fromConfig(section(root + "sword")), key);
        helmet = ItemBuilder.fromConfig(section(root + "armor.helmet"));
        chestplate = ItemBuilder.fromConfig(section(root + "armor.chestplate"));
        leggings = ItemBuilder.fromConfig(section(root + "armor.leggings"));
        boots = ItemBuilder.fromConfig(section(root + "armor.boots"));

        listen(this);
        repeat(this::tick, 20L, 20L);

        for (Player player : plugin.getServer().getOnlinePlayers()) give(player);
    }

    @Override
    protected void shutdown() {
        // Everyone in PvP gets their inventory back before the module goes away — otherwise the
        // snapshot dies with it and the player is left holding a kit they never owned.
        for (Map.Entry<UUID, PvpSession> entry : Map.copyOf(active).entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                entry.getValue().restore(plugin, player);
                heal(player);
            }
        }
        active.clear();
        arming.clear();
        disarming.clear();
        combatTag.clear();
        tagNotified.clear();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ItemStack current = player.getInventory().getItem(swordSlot);
            if (ItemBuilder.hasTag(current, key)) player.getInventory().setItem(swordSlot, null);
        }
    }

    public boolean isActive(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    public int activeCount() {
        return active.size();
    }

    private boolean isSword(ItemStack item) {
        return ItemBuilder.hasTag(item, key);
    }

    private void give(Player player) {
        if (!giveOnJoin || !inLobbyWorld(player.getWorld())) return;
        player.getInventory().setItem(swordSlot, sword.clone());
    }

    private void heal(Player player) {
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) player.setHealth(maxHealth.getValue());
        player.setFoodLevel(20);
        player.setFireTicks(0);
    }

    // ------------------------------------------------------------- transitions

    private void activate(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();

        active.put(uuid, PvpSession.capture(player));

        inventory.clear();
        for (var effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.setFlying(false);
        player.setAllowFlight(false);

        inventory.setHelmet(helmet.clone());
        inventory.setChestplate(chestplate.clone());
        inventory.setLeggings(leggings.clone());
        inventory.setBoots(boots.clone());
        inventory.setItem(ACTIVE_SLOT, sword.clone());
        inventory.setHeldItemSlot(ACTIVE_SLOT);

        heal(player);
        managers.activity().record(ActivityLog.PVP, player, "attivato");
        managers.messages().send(player, "pvp-activated");
        Sounds.play(player, activateSound, 0.5f, 1.2f);
    }

    private void deactivate(Player player) {
        PvpSession session = active.remove(player.getUniqueId());
        if (session == null) return;

        session.restore(plugin, player);
        heal(player);
        managers.activity().record(ActivityLog.PVP, player, "disattivato");
        managers.messages().send(player, "pvp-deactivated");
        Sounds.play(player, deactivateSound, 1f, 0.8f);
    }

    /**
     * One second of every countdown. Deliberately a single task rather than one per player: a
     * lobby has hundreds of players and at most a handful in PvP, and three map walks a second
     * over empty maps costs nothing.
     */
    private void tick() {
        tickArming();
        tickCombatTags();
        tickDisarming();
    }

    private void tickArming() {
        Iterator<Map.Entry<UUID, Integer>> iterator = arming.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());

            // Stop the moment they stop holding it — the countdown is "keep holding this".
            if (player == null || !player.isOnline()
                    || !isSword(player.getInventory().getItemInMainHand())) {
                iterator.remove();
                continue;
            }

            int seconds = entry.getValue();
            if (seconds > 0) {
                entry.setValue(seconds - 1);
                managers.messages().send(player, "pvp-countdown", Map.of("%seconds%", seconds));
                if (countdownSound) {
                    Sounds.play(player, countdownSoundName, countdownVolume, countdownPitch);
                }
            } else {
                iterator.remove();
                activate(player);
            }
        }
    }

    private void tickDisarming() {
        Iterator<Map.Entry<UUID, Integer>> iterator = disarming.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            int seconds = entry.getValue();
            if (seconds > 0) {
                entry.setValue(seconds - 1);
                managers.messages().send(player, "pvp-countdown-off", Map.of("%seconds%", seconds));
                if (countdownSound) {
                    Sounds.play(player, countdownSoundName, countdownVolume, countdownPitch);
                }
            } else {
                iterator.remove();
                deactivate(player);
            }
        }
    }

    private void tickCombatTags() {
        Iterator<Map.Entry<UUID, Integer>> iterator = combatTag.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            UUID uuid = entry.getKey();
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                iterator.remove();
                tagNotified.remove(uuid);
                continue;
            }

            int seconds = entry.getValue();
            if (seconds > 1) {
                entry.setValue(seconds - 1);
                continue;
            }

            iterator.remove();
            tagNotified.remove(uuid);
            // The tag was the only thing holding them in. If they already put the sword away,
            // start the exit countdown now rather than making them switch slots again.
            if (!isSword(player.getInventory().getItemInMainHand()) && !disarming.containsKey(uuid)) {
                disarming.put(uuid, releaseSeconds);
            }
        }
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        later(() -> {
            if (event.getPlayer().isOnline()) give(event.getPlayer());
        }, Math.max(1L, giveDelayTicks));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean holdingSword = isSword(player.getInventory().getItem(event.getNewSlot()));

        if (holdingSword) {
            disarming.remove(uuid);
            if (active.containsKey(uuid) || arming.containsKey(uuid)) return;
            arming.put(uuid, holdSeconds);
            return;
        }

        if (active.containsKey(uuid)) {
            Integer tag = combatTag.get(uuid);
            if (tag != null && tag > 0) {
                // Put them straight back on the sword: cancelling alone leaves the client
                // showing the slot it picked.
                event.setCancelled(true);
                player.getInventory().setHeldItemSlot(ACTIVE_SLOT);
                if (tagNotified.putIfAbsent(uuid, Boolean.TRUE) == null) {
                    managers.messages().send(player, "pvp-combat-tagged", Map.of("%seconds%", tag));
                }
                return;
            }
        }

        arming.remove(uuid);
        if (active.containsKey(uuid) && !disarming.containsKey(uuid)) {
            disarming.put(uuid, releaseSeconds);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isSword(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (active.containsKey(uuid)) {
            event.setCancelled(true);
            return;
        }
        // Swapping the sword to the off hand would stop the "holding it" countdown silently.
        arming.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (MenuManager.isMenu(player)) return;

        if (isSword(event.getCurrentItem()) || isSword(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        if (!active.containsKey(player.getUniqueId())) return;
        // While active the whole kit is off limits: the sword slot, the armour slots, and the
        // hotbar key that would swap something into the sword slot.
        int raw = event.getRawSlot();
        if (event.getSlot() == ACTIVE_SLOT || event.getHotbarButton() == ACTIVE_SLOT
                || (raw >= 5 && raw <= 8) || (event.getSlot() >= 36 && event.getSlot() <= 39)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!active.containsKey(player.getUniqueId())) return;
        // No lobby speed boost inside a fight.
        var action = event.getAction();
        if (action == EntityPotionEffectEvent.Action.ADDED
                || action == EntityPotionEffectEvent.Action.CHANGED) {
            event.setCancelled(true);
        }
    }

    /**
     * Damage between players is allowed only when both sides opted in.
     *
     * <p>HIGHEST and not {@code ignoreCancelled}: the world-settings module has usually already
     * cancelled this, and two active fighters are the one case that has to override it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        boolean both = active.containsKey(victim.getUniqueId())
                && active.containsKey(attacker.getUniqueId());
        if (!both) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(false);

        if (combatTagSeconds <= 0) return;
        for (UUID uuid : new UUID[] { victim.getUniqueId(), attacker.getUniqueId() }) {
            combatTag.put(uuid, combatTagSeconds);
            disarming.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID uuid = victim.getUniqueId();
        if (!active.containsKey(uuid)) return;

        // The kit is ours, not loot.
        event.getDrops().clear();
        event.setDroppedExp(0);

        if (deathParticle && victim.getWorld() != null) {
            Location at = victim.getLocation().add(0, 1, 0);
            victim.getWorld().spawnParticle(deathParticleType, at, deathParticleCount, 0.5, 0.5, 0.5, 0.1);
        }

        PlayerDataManager.LobbyPlayer victimData = managers.playerData().get(victim);
        if (victimData != null) victimData.addDeath();

        Player killer = victim.getKiller();
        if (killer != null && active.containsKey(killer.getUniqueId())) {
            PlayerDataManager.LobbyPlayer killerData = managers.playerData().get(killer);
            if (killerData != null) killerData.addKill();

            Component line = C.mm(C.replace(managers.messages().raw("pvp-kill"), Map.of(
                    "%killer%", killer.getName(),
                    "%victim%", victim.getName())));
            for (Player online : plugin.getServer().getOnlinePlayers()) online.sendMessage(line);
            managers.activity().record(ActivityLog.KILL, killer, "ha ucciso " + victim.getName());
            heal(killer);
        }

        // The session stays: the respawn handler is what gives the inventory back, and losing it
        // here would mean dying in PvP wipes whatever you were carrying.
        combatTag.remove(uuid);
        tagNotified.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PvpSession session = active.remove(player.getUniqueId());
        if (session == null) return;

        // One tick after the respawn: setting inventory inside the event is overwritten by the
        // server's own respawn handling.
        later(() -> {
            if (!player.isOnline()) return;
            session.restore(plugin, player);
            heal(player);
            give(player);
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        arming.remove(uuid);
        disarming.remove(uuid);
        combatTag.remove(uuid);
        tagNotified.remove(uuid);

        PvpSession session = active.remove(uuid);
        if (session != null) {
            // Restore before the player object goes away, so the inventory Bukkit saves to disk
            // is the one they actually owned.
            session.restore(plugin, player);
        }
    }
}
