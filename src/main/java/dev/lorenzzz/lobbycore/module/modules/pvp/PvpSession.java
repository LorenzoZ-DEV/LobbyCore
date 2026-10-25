package dev.lorenzzz.lobbycore.module.modules.pvp;

import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;
import java.util.List;

/**
 * Everything taken away from a player when they enter lobby PvP, so it can be given back when
 * they leave.
 *
 * <p>Holding it in one object rather than in a map per field is the whole point: the original
 * kept inventory, flight state and potion effects in three separate caches keyed by UUID, and a
 * code path that removed a player from two of the three left a fragment behind — which is how a
 * player ends up unable to fly after a disconnect mid-fight. One object means one removal.
 */
public final class PvpSession {

    private final ItemStack[] contents;
    private final ItemStack[] armour;
    private final boolean allowedFlight;
    private final Collection<PotionEffect> effects;
    private final int heldSlot;

    private PvpSession(ItemStack[] contents, ItemStack[] armour, boolean allowedFlight,
                       Collection<PotionEffect> effects, int heldSlot) {
        this.contents = contents;
        this.armour = armour;
        this.allowedFlight = allowedFlight;
        this.effects = effects;
        this.heldSlot = heldSlot;
    }

    /** Snapshots a player's current state. */
    public static PvpSession capture(org.bukkit.entity.Player player) {
        var inventory = player.getInventory();
        return new PvpSession(
                inventory.getContents().clone(),
                inventory.getArmorContents().clone(),
                player.getAllowFlight(),
                List.copyOf(player.getActivePotionEffects()),
                inventory.getHeldItemSlot());
    }

    /** Puts the snapshot back. Safe to call on a player who has since changed worlds. */
    public void restore(org.bukkit.plugin.Plugin plugin, org.bukkit.entity.Player player) {
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setContents(contents);
        inventory.setArmorContents(armour);
        inventory.setHeldItemSlot(Math.max(0, Math.min(8, heldSlot)));

        for (PotionEffect active : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(active.getType());
        }
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }

        if (allowedFlight) player.setAllowFlight(true);
    }
}
