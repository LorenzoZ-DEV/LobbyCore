package dev.lorenzzz.lobbycore.module.modules.world;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.Sounds;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pressure plates that fling a player across the lobby.
 *
 * <p>The trigger is the move event rather than {@code PlayerInteractEvent}'s PHYSICAL action,
 * because the world-settings module denies block interaction and would eat the plate press. It
 * only reads the block a player is standing on when they actually change block — a move event
 * fires for a head turn too, and reading a block for every one of those is a chunk lookup per
 * player per tick for nothing.
 */
public final class LaunchpadModule extends AbstractModule implements Listener {

    private final Set<Material> plates = EnumSet.noneOf(Material.class);

    private double power;
    private double verticalPower;
    private String sound;
    private float volume;
    private float pitch;
    private Particle particle;
    private int particleCount;

    public LaunchpadModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "launchpad"; }
    @Override public String configFile() { return "launchpad"; }

    @Override
    public String description() {
        return "Pedane di lancio";
    }

    @Override
    public void onEnable() {
        var config = config();
        power = config.getDouble("launchpad.power", 2.0);
        verticalPower = config.getDouble("launchpad.vertical_power", 1.0);
        sound = config.getString("launchpad.sound.name", "ENTITY_BAT_TAKEOFF");
        volume = (float) config.getDouble("launchpad.sound.volume", 1.0);
        pitch = (float) config.getDouble("launchpad.sound.pitch", 1.0);
        particleCount = config.getInt("launchpad.particle.count", 25);

        try {
            particle = Particle.valueOf(config.getString("launchpad.particle.type", "CLOUD")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            particle = Particle.CLOUD;
        }

        plates.clear();
        for (String name : config.getStringList("launchpad.blocks")) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material != null) plates.add(material);
        }
        if (plates.isEmpty()) plates.add(Material.HEAVY_WEIGHTED_PRESSURE_PLATE);

        listen(this);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;

        Player player = event.getPlayer();
        if (!inLobbyWorld(player.getWorld())) return;

        Material standing = event.getTo().getBlock().getType();
        if (!plates.contains(standing)) {
            // A plate can also be the block the player's feet are inside rather than below them,
            // depending on the model; checking both costs one extra lookup only on a real move.
            standing = event.getTo().clone().subtract(0, 1, 0).getBlock().getType();
            if (!plates.contains(standing)) return;
        }

        Vector launch = player.getLocation().getDirection().multiply(power).setY(verticalPower);
        player.setVelocity(launch);

        Sounds.play(player, sound, volume, pitch);
        if (particleCount > 0) {
            player.getWorld().spawnParticle(particle, player.getLocation(), particleCount,
                    0.4, 0.1, 0.4, 0.05);
        }
    }
}
