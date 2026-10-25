package dev.lorenzzz.lobbycore.module.modules.blocks;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.menu.MenuManager;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.ItemBuilder;
import dev.lorenzzz.lobbycore.util.Region;
import dev.lorenzzz.lobbycore.util.Sounds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "magic blocks" a player can place in the lobby, which crack and vanish a few seconds
 * later. A fork of nLobbyBlocks, folded in here so it shares the hotbar, the region list and the
 * protection rules with everything else rather than fighting them.
 *
 * <p>Two things from the original are kept deliberately. Placed blocks are keyed by a single
 * packed {@code long} rather than a {@code Location}: the break check runs inside
 * {@link BlockBreakEvent} and on every explosion's block list, and a long key is one hash of one
 * primitive instead of a Location's world reference and three doubles. And the crack overlay is
 * sent only to players within 32 blocks, because it is a packet per block per player per second
 * and nobody sees a block they are not near.
 *
 * <p>What changed: the timer is a normal Bukkit task rather than the Folia async/region pair, and
 * block edits happen on the main thread. Paper needs that, and the original's region scheduler
 * calls were a no-op detour there.
 */
public final class LobbyBlocksModule extends AbstractModule implements Listener {

    /** A crack overlay further away than this is a packet nobody can see. */
    private static final double CRACK_RANGE_SQUARED = 32.0 * 32.0;

    private static final BlockData AIR = Material.AIR.createBlockData();

    /** One placed block and how long it has left. */
    private static final class Placed {
        private final Location location;
        private final Block block;
        private final World world;
        private final double x, y, z;
        private final int crackId;
        private int elapsedSeconds;

        Placed(Location location) {
            this.location = location;
            this.block = location.getBlock();
            this.world = location.getWorld();
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
            // The crack animation is keyed by a "source entity" id; any stable number unique to
            // the block works, and packing its coordinates gives one for free.
            this.crackId = (block.getX() & 4095) << 20 | (block.getZ() & 4095) << 8 | (block.getY() & 255);
        }
    }

    private final Map<Long, Placed> placed = new ConcurrentHashMap<>();
    private final List<Region> regions = new ArrayList<>();

    private NamespacedKey key;
    private Material material;
    private int amount;
    private int hotbarSlot;
    private int breakAfterSeconds;
    private boolean giveOnJoin;
    private long giveDelayTicks;
    private boolean preventDrop;
    private boolean preventMove;
    private boolean preventOffHandSwap;
    private boolean preventInteraction;

    private ItemStack template;

    private boolean placeSound;
    private String placeSoundName;
    private float placeVolume, placePitch;
    private boolean breakSound;
    private String breakSoundName;
    private float breakVolume, breakPitch;

    public LobbyBlocksModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "lobby-blocks"; }
    @Override public String configFile() { return "lobby_blocks"; }

    @Override
    public String description() {
        return "Blocchi piazzabili che spariscono da soli (fork di nLobbyBlocks)";
    }

    @Override
    public void onEnable() {
        key = new NamespacedKey(plugin, "lobby_block");
        var config = config();
        String root = "lobby_blocks.";

        material = ItemBuilder.material(config.getString(root + "block_type", "MOSS_BLOCK"));
        if (!material.isBlock()) {
            C.warning("block_type '" + material + "' non e un blocco — uso MOSS_BLOCK.");
            material = Material.MOSS_BLOCK;
        }
        amount = Math.max(1, Math.min(64, config.getInt(root + "block_count", 64)));
        hotbarSlot = Math.max(0, Math.min(8, config.getInt(root + "hotbar_slot", 0)));
        breakAfterSeconds = Math.max(1, config.getInt(root + "break_after", 10));
        giveOnJoin = config.getBoolean(root + "blocks_on_join", true);
        giveDelayTicks = config.getLong(root + "join_give_delay", 20L);
        preventDrop = config.getBoolean(root + "prevent_drop", true);
        preventMove = config.getBoolean(root + "prevent_move", true);
        preventOffHandSwap = config.getBoolean(root + "prevent_off_hand_swap", true);
        preventInteraction = config.getBoolean(root + "prevent_block_interaction", false);

        placeSound = config.getBoolean(root + "sounds.place.enabled", false);
        placeSoundName = config.getString(root + "sounds.place.sound", "BLOCK_NOTE_BLOCK_PLING");
        placeVolume = (float) config.getDouble(root + "sounds.place.volume", 1.0);
        placePitch = (float) config.getDouble(root + "sounds.place.pitch", 1.2);

        breakSound = config.getBoolean(root + "sounds.break.enabled", false);
        breakSoundName = config.getString(root + "sounds.break.sound", "BLOCK_GRASS_BREAK");
        breakVolume = (float) config.getDouble(root + "sounds.break.volume", 1.0);
        breakPitch = (float) config.getDouble(root + "sounds.break.pitch", 1.0);

        regions.clear();
        regions.addAll(Region.parseAll(config.getConfigurationSection(root + "regions")));

        template = buildItem(config.getString(root + "block_name", ""));

        listen(this);
        repeat(this::tick, 20L, 20L);

        for (Player player : plugin.getServer().getOnlinePlayers()) give(player);
    }

    @Override
    protected void shutdown() {
        // Every tracked block goes back to air. Leaving them would litter the lobby with blocks
        // nothing is counting down any more, and the next start would not know about them.
        for (Placed entry : placed.values()) {
            entry.block.setBlockData(AIR, false);
        }
        placed.clear();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                if (ItemBuilder.hasTag(contents[slot], key)) player.getInventory().setItem(slot, null);
            }
        }
    }

    private ItemStack buildItem(String name) {
        ItemStack item = new ItemStack(material, amount);
        var meta = item.getItemMeta();
        if (meta != null && name != null && !name.isEmpty()) {
            meta.displayName(C.item(name));
            item.setItemMeta(meta);
        }
        return ItemBuilder.tag(item, key);
    }

    private boolean isLobbyBlock(ItemStack item) {
        return item != null && item.getType() == material && ItemBuilder.hasTag(item, key);
    }

    /** Tops the player's stack back up, or puts a fresh one in the configured slot. */
    public void give(Player player) {
        if (!inLobbyWorld(player.getWorld())) return;

        var inventory = player.getInventory();
        int held = inventory.getHeldItemSlot();
        ItemStack inHand = inventory.getItem(held);
        if (isLobbyBlock(inHand)) {
            inHand.setAmount(amount);
            return;
        }

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isLobbyBlock(item)) {
                item.setAmount(amount);
                return;
            }
        }

        ItemStack existing = inventory.getItem(hotbarSlot);
        if (existing == null || existing.getType() == Material.AIR) {
            inventory.setItem(hotbarSlot, template.clone());
        } else {
            inventory.addItem(template.clone());
        }
    }

    /** Counts every tracked block down a second, cracking the survivors and clearing the rest. */
    private void tick() {
        if (placed.isEmpty()) return;

        List<Player> viewers = new ArrayList<>(plugin.getServer().getOnlinePlayers());

        Iterator<Placed> iterator = placed.values().iterator();
        while (iterator.hasNext()) {
            Placed entry = iterator.next();
            entry.elapsedSeconds++;

            if (entry.elapsedSeconds >= breakAfterSeconds) {
                iterator.remove();
                entry.block.setBlockData(AIR, false);
                if (breakSound) Sounds.playAt(entry.location, breakSoundName, breakVolume, breakPitch);
                continue;
            }
            sendCrack(entry, viewers);
        }
    }

    private void sendCrack(Placed entry, List<Player> viewers) {
        float progress = (float) entry.elapsedSeconds / breakAfterSeconds;
        for (Player viewer : viewers) {
            Location at = viewer.getLocation();
            if (at.getWorld() != entry.world) continue;
            double dx = at.getX() - entry.x;
            double dy = at.getY() - entry.y;
            double dz = at.getZ() - entry.z;
            if (dx * dx + dy * dy + dz * dz > CRACK_RANGE_SQUARED) continue;
            viewer.sendBlockDamage(entry.location, progress, entry.crackId);
        }
    }

    private static long packKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private boolean isTracked(Block block) {
        return !placed.isEmpty()
                && placed.containsKey(packKey(block.getX(), block.getY(), block.getZ()));
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!giveOnJoin) return;
        Player player = event.getPlayer();
        if (giveDelayTicks <= 0) {
            give(player);
            return;
        }
        later(() -> {
            if (player.isOnline()) give(player);
        }, giveDelayTicks);
    }

    /**
     * Runs last and explicitly un-cancels.
     *
     * <p>The world-settings module cancels every placement in the lobby, which is exactly what it
     * should do — this is the one exception, and an exception has to be applied after the rule,
     * not before it. Hence HIGHEST, and {@code ignoreCancelled = false} so the handler still sees
     * an event somebody else already cancelled. Nothing else in the plugin un-cancels anything,
     * so the interaction is confined to these two handlers.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!isLobbyBlock(event.getItemInHand())) return;

        Player player = event.getPlayer();
        Location location = event.getBlockPlaced().getLocation();

        if (!Region.allowed(regions, location)) {
            event.setCancelled(true);
            managers.messages().send(player, "blocks-region-denied");
            return;
        }
        event.setCancelled(false);

        placed.put(packKey(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                new Placed(location));
        managers.activity().record(ActivityLog.BLOCK, player,
                location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ());
        if (placeSound) Sounds.playAt(location, placeSoundName, placeVolume, placePitch);

        // Hand the stack straight back, so the count never runs down. Setting the item rather
        // than adding one keeps it in the slot the player is holding.
        ItemStack refill = template.clone();
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(refill);
        } else {
            player.getInventory().setItemInMainHand(refill);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isTracked(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (placed.isEmpty()) return;
        event.blockList().removeIf(this::isTracked);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (placed.isEmpty()) return;
        event.blockList().removeIf(this::isTracked);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!preventInteraction || event.getClickedBlock() == null) return;
        if (isTracked(event.getClickedBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (preventDrop && isLobbyBlock(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!preventOffHandSwap) return;
        if (isLobbyBlock(event.getMainHandItem()) || isLobbyBlock(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!preventMove || MenuManager.isMenu(event.getWhoClicked())) return;
        if (isLobbyBlock(event.getCurrentItem()) || isLobbyBlock(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (preventMove && isLobbyBlock(event.getOldCursor())) event.setCancelled(true);
    }

    /** How many blocks are currently counting down — surfaced as a placeholder. */
    /** Lo slot dei blocchi, cosi gli altri moduli possono accorgersi di volerlo anche loro. */
    public int slot() {
        return hotbarSlot;
    }

    public int activeBlocks() {
        return placed.size();
    }
}
