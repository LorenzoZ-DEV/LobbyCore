package dev.lorenzzz.lobbycore.module.modules.bow;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.menu.MenuManager;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.module.modules.blocks.LobbyBlocksModule;
import dev.lorenzzz.lobbycore.module.modules.hotbar.HotbarModule;
import dev.lorenzzz.lobbycore.module.modules.hotbar.PlayerHiderModule;
import dev.lorenzzz.lobbycore.util.ItemBuilder;
import dev.lorenzzz.lobbycore.util.Sounds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The bow that teleports you to wherever the arrow lands. A fork of l-teleportbow.
 *
 * <p>The trail is the part that needed rethinking. The original ran a task every tick over a
 * {@link HashSet} of live arrows for the whole server — fine, but the set was only ever pruned
 * from inside that same task, so an arrow that despawned between ticks stayed referenced. Here
 * the set is still walked each tick, but the sweep drops anything dead, invalid or grounded
 * <em>and</em> the task stops scheduling work when the set is empty, which is almost always.
 *
 * <p>Cooldowns are a Caffeine cache rather than a map, so a player who leaves does not keep an
 * entry alive until the next restart.
 */
public final class TeleportBowModule extends AbstractModule implements Listener {

    private NamespacedKey key;

    private ItemStack bow;
    private ItemStack arrows;
    private int bowSlot;
    private int arrowSlot;
    private boolean arrowInInventory;

    private boolean requirePermission;
    private String usePermission;
    private String bypassPermission;
    private long cooldownMillis;

    private Particle trailParticle;
    private int trailCount;
    private Particle arriveParticle;
    private int arriveCount;
    private String arriveSound;
    private float arriveVolume, arrivePitch;

    private boolean giveOnJoin;
    private boolean protectItems;

    /** Arrows currently in flight that came from a teleport bow. */
    private final Set<Arrow> tracked = new HashSet<>();

    private final Cache<UUID, Long> cooldowns = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    public TeleportBowModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "teleport-bow"; }
    @Override public String configFile() { return "teleport_bow"; }

    @Override
    public String description() {
        return "Arco che teletrasporta dove atterra la freccia (fork di l-teleportbow)";
    }

    @Override
    public void onEnable() {
        key = new NamespacedKey(plugin, "teleport_bow");
        var config = config();
        String root = "teleport_bow.";

        bowSlot = Math.max(0, Math.min(8, config.getInt(root + "bow.item_slot", 0)));
        arrowSlot = Math.max(0, Math.min(8, config.getInt(root + "arrow.item_slot", 1)));
        arrowInInventory = config.getBoolean(root + "arrow.in_inventory", true);
        giveOnJoin = config.getBoolean(root + "give_on_join", true);
        protectItems = config.getBoolean(root + "protect_items", true);

        requirePermission = config.getBoolean(root + "permissions.require_use", false);
        usePermission = config.getString(root + "permissions.use", "lobbycore.teleportbow.use");
        bypassPermission = config.getString(root + "permissions.cooldown_bypass",
                "lobbycore.teleportbow.bypass");
        cooldownMillis = Math.max(0L, config.getLong(root + "teleport.cooldown_seconds", 3L)) * 1000L;

        trailParticle = particle(config.getString(root + "arrow.trail_particle", "HEART"), Particle.HEART);
        trailCount = config.getInt(root + "arrow.trail_particle_count", 3);
        arriveParticle = particle(config.getString(root + "teleport.particle", "PORTAL"), Particle.PORTAL);
        arriveCount = config.getInt(root + "teleport.particle_count", 50);
        arriveSound = config.getString(root + "teleport.sound", "ENTITY_ENDERMAN_TELEPORT");
        arriveVolume = (float) config.getDouble(root + "teleport.sound_volume", 1.0);
        arrivePitch = (float) config.getDouble(root + "teleport.sound_pitch", 1.0);

        bow = buildBow();
        arrows = buildArrows(Math.max(1, config.getInt(root + "arrow.amount", 1)));

        listen(this);
        repeat(this::tickTrails, 1L, 1L);
        warnSlotConflicts();

        for (Player player : plugin.getServer().getOnlinePlayers()) give(player);
    }

    /**
     * Avvisa quando un altro modulo ha gia preso uno degli slot dell'arco.
     *
     * <p>Non si sposta niente da soli — quale item debba vincere lo decide l'admin — ma il
     * conflitto va detto: un item sovrascritto in silenzio all'insaputa di tutti e esattamente
     * il problema per cui questi plugin sono stati uniti in uno solo.
     */
    private void warnSlotConflicts() {
        HotbarModule hotbar = managers.modules().module("hotbar", HotbarModule.class);
        if (hotbar != null) {
            for (int claimed : hotbar.claimedSlots()) {
                if (claimed == bowSlot) warnSlot("hotbar", "l'arco", bowSlot);
                if (!arrowInInventory && claimed == arrowSlot) warnSlot("hotbar", "le frecce", arrowSlot);
            }
        }
        PlayerHiderModule hider = managers.modules().module("player-hider", PlayerHiderModule.class);
        if (hider != null) {
            if (hider.slot() == bowSlot) warnSlot("player-hider", "l'arco", bowSlot);
            if (!arrowInInventory && hider.slot() == arrowSlot) {
                warnSlot("player-hider", "le frecce", arrowSlot);
            }
        }
        // I due stanno di proposito uno accanto all'altro: un decimo di errore nel file e
        // finiscono sopra, ed e il tipo di conflitto che vogliamo sentire dire, non scoprire.
        LobbyBlocksModule blocks = managers.modules().module("lobby-blocks", LobbyBlocksModule.class);
        if (blocks != null) {
            if (blocks.slot() == bowSlot) warnSlot("lobby-blocks", "l'arco", bowSlot);
            if (!arrowInInventory && blocks.slot() == arrowSlot) {
                warnSlot("lobby-blocks", "le frecce", arrowSlot);
            }
        }
    }

    private static void warnSlot(String other, String what, int slot) {
        C.warning("Lo slot " + slot + " e conteso fra " + what + " di teleport-bow e il modulo '"
                + other + "' — uno dei due item sparira. Cambia slot in uno dei due file.");
    }

    @Override
    protected void shutdown() {
        tracked.clear();
        cooldowns.invalidateAll();
        for (Player player : plugin.getServer().getOnlinePlayers()) takeBack(player);
    }

    /** Toglie arco e frecce, ovunque siano finiti nell'inventario. */
    private void takeBack(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (ItemBuilder.hasTag(contents[slot], key)) player.getInventory().setItem(slot, null);
        }
    }

    private static Particle particle(String name, Particle fallback) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }

    private ItemStack buildBow() {
        var section = section("teleport_bow.bow");
        // ItemBuilder falls back to STONE for a missing material; a bow module wants a bow.
        if (!section.contains("material")) section.set("material", "BOW");
        return ItemBuilder.tag(ItemBuilder.fromConfig(section), key);
    }

    private ItemStack buildArrows(int count) {
        ItemStack item = new ItemStack(Material.ARROW, count);
        return ItemBuilder.tag(item, key);
    }

    public void give(Player player) {
        if (!giveOnJoin || !inLobbyWorld(player.getWorld())) return;
        var inventory = player.getInventory();
        inventory.setItem(bowSlot, bow.clone());

        if (!arrowInInventory) {
            inventory.setItem(arrowSlot, arrows.clone());
            return;
        }
        // L'arco ha Infinity di default: basta UNA freccia addosso per scoccare, quindi sta
        // nell'inventario principale (slot 9-35) e mai nella hotbar. Niente addItem: a
        // inventario vuoto riempie dal primo slot libero, che e nella hotbar.
        sweepArrows(inventory);
        ItemStack stack = arrows.clone();
        for (int slot = 9; slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() == Material.AIR) {
                inventory.setItem(slot, stack);
                return;
            }
        }
        // Inventario pieno: ultima spiaggia, lo slot hotbar configurato.
        inventory.setItem(arrowSlot, stack);
    }

    /**
     * Toglie tutte le frecce marchiate, ovunque siano. Migra anche quelle rimaste in
     * hotbar dalle versioni precedenti, cosi non restano doppioni in giro.
     */
    private void sweepArrows(org.bukkit.inventory.PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == Material.ARROW && ItemBuilder.hasTag(item, key)) {
                inventory.setItem(slot, null);
            }
        }
    }

    /**
     * Draws the trail behind every live arrow and prunes the ones that are done.
     *
     * <p>Runs every tick, which sounds expensive until you notice the first line: with no arrow
     * in flight — the state the lobby is in nearly all the time — this is one {@code isEmpty}
     * check and a return.
     */
    private void tickTrails() {
        if (tracked.isEmpty()) return;

        Iterator<Arrow> iterator = tracked.iterator();
        while (iterator.hasNext()) {
            Arrow arrow = iterator.next();
            if (arrow.isDead() || !arrow.isValid() || arrow.isOnGround()) {
                iterator.remove();
                continue;
            }
            if (trailCount > 0) {
                arrow.getWorld().spawnParticle(trailParticle, arrow.getLocation(), trailCount, 0, 0, 0, 0);
            }
        }
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        later(() -> {
            if (event.getPlayer().isOnline()) give(event.getPlayer());
        }, 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;
        if (!ItemBuilder.hasTag(event.getBow(), key)) return;

        // The tag goes on the arrow too: the hit handler only sees the projectile, and an arrow
        // from an ordinary bow must not teleport anyone.
        arrow.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        tracked.add(arrow);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (arrow.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            // A teleport arrow is a movement tool, not a weapon.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player player)) return;
        if (!arrow.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;

        tracked.remove(arrow);
        // La posizione si legge prima di remove(): dopo, la freccia e fuori dal mondo e
        // getLocation() torna un residuo su cui non vale la pena contare.
        Location destination = arrow.getLocation().clone();
        arrow.remove();

        if (requirePermission && !player.hasPermission(usePermission)) {
            managers.messages().send(player, "bow-no-permission");
            return;
        }

        boolean bypass = bypassPermission != null && !bypassPermission.isEmpty()
                && player.hasPermission(bypassPermission);

        if (!bypass && cooldownMillis > 0) {
            Long last = cooldowns.getIfPresent(player.getUniqueId());
            long now = System.currentTimeMillis();
            if (last != null && now - last < cooldownMillis) {
                long remaining = (cooldownMillis - (now - last) + 999) / 1000;
                managers.messages().send(player, "bow-cooldown", Map.of("%seconds%", remaining));
                return;
            }
            cooldowns.put(player.getUniqueId(), now);
        }

        // Keep the player's own facing: arriving turned to face wherever the arrow happened to
        // be pointing is disorienting.
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());

        managers.activity().record(ActivityLog.BOW, player,
                destination.getBlockX() + " " + destination.getBlockY() + " " + destination.getBlockZ());

        player.teleportAsync(destination).thenAccept(success -> {
            if (!success) return;
            if (arriveCount > 0) {
                destination.getWorld().spawnParticle(arriveParticle, destination, arriveCount, 0.5, 1, 0.5);
            }
            Sounds.playAt(destination, arriveSound, arriveVolume, arrivePitch);
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Dopo la morte l'inventario e vuoto e l'arco non tornava piu: era l'unico modulo a
        // item senza questo passaggio, hotbar e player-hider ce l'avevano gia. Un tick dopo il
        // respawn, per non farselo sovrascrivere dalla gestione del server.
        later(() -> {
            Player player = event.getPlayer();
            if (player.isOnline() && inLobbyWorld(player.getWorld())) give(player);
        }, 1L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // Come per la hotbar: l'arco veniva dato solo al join, quindi chi usciva dalla lobby se
        // lo portava dietro e chi rientrava restava a mani vuote fino al login dopo.
        Player player = event.getPlayer();
        if (inLobbyWorld(player.getWorld())) {
            give(player);
        } else {
            takeBack(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (protectItems && ItemBuilder.hasTag(event.getItemDrop().getItemStack(), key)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!protectItems || MenuManager.isMenu(event.getWhoClicked())) return;
        if (ItemBuilder.hasTag(event.getCurrentItem(), key)
                || ItemBuilder.hasTag(event.getCursor(), key)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (protectItems && ItemBuilder.hasTag(event.getOldCursor(), key)) event.setCancelled(true);
    }
}
