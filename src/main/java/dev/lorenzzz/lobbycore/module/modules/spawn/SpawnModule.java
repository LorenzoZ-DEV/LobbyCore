package dev.lorenzzz.lobbycore.module.modules.spawn;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Owns the lobby spawn point: where {@code /spawn} sends people, where respawns land, and where
 * the void catcher in the world-settings module puts anyone who falls off.
 *
 * <p>The location lives in its own {@code spawn.yml} rather than in a module config, because it
 * is the one value an admin sets in game with a command and not by editing a file — mixing
 * written-by-hand options with plugin-written state in the same file means a reload can silently
 * lose one or the other.
 */
public final class SpawnModule extends AbstractModule implements Listener {

    private File file;
    private YamlConfiguration yaml;

    /**
     * The spawn as it is on disk: a world *name* plus coordinates, not a {@link Location}.
     *
     * <p>Kept in this form on purpose. A Location holds a live {@link World}, so resolving one at
     * enable time freezes the answer to "is that world loaded yet?" — and on a server where the
     * lobby world is loaded by a world manager (Multiverse and friends) that answer is "no": we
     * enable first, the world appears a moment later, and the spawn stays null until someone runs
     * {@code /setspawn} again even though spawn.yml was never wrong.
     */
    private volatile Placed placed;
    /** Cache of the resolved location, filled the first time the world is actually there. */
    private volatile Location spawn;
    /** So a missing world is reported once and not on every teleport attempt. */
    private boolean warnedMissingWorld;

    private record Placed(String world, double x, double y, double z, float yaw, float pitch) {
        Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    private boolean teleportOnJoin;
    private boolean teleportOnRespawn;

    public SpawnModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "spawn"; }
    @Override public String configFile() { return "spawn"; }

    @Override
    public String description() {
        return "Punto di spawn della lobby, /spawn e /setspawn";
    }

    @Override
    public void onEnable() {
        teleportOnJoin = config().getBoolean("spawn.teleport_on_join", true);
        teleportOnRespawn = config().getBoolean("spawn.teleport_on_respawn", true);

        file = new File(plugin.getDataFolder(), "spawn.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
        placed = read();
        spawn = null;
        warnedMissingWorld = false;

        listen(this);
        command(new SpawnCommands());
    }

    @Override
    protected void shutdown() {
        spawn = null;
        placed = null;
    }

    /**
     * The configured spawn, or {@code null} when nobody has set one yet — or when the world it
     * names still is not loaded.
     *
     * <p>Resolved here rather than at enable so a world that shows up later (a world manager
     * loading it after us) is picked up on its own, without a reload.
     */
    public Location spawn() {
        Location cached = spawn;
        if (cached != null) return cached;

        Placed current = placed;
        if (current == null) return null;

        World world = Bukkit.getWorld(current.world());
        if (world == null) {
            if (!warnedMissingWorld) {
                warnedMissingWorld = true;
                C.warning("Lo spawn punta al mondo '" + current.world() + "', che non e caricato. "
                        + "Le coordinate in spawn.yml restano salvate: ricontrollero quando il mondo c'e.");
            }
            return null;
        }

        Location resolved = current.toLocation(world);
        spawn = resolved;
        warnedMissingWorld = false;
        return resolved;
    }

    public boolean teleportOnJoin() {
        return teleportOnJoin;
    }

    /** Sends a player to spawn. False when no spawn is set, so callers can say so. */
    public boolean send(Player player) {
        Location target = spawn();
        if (target == null) return false;
        player.teleportAsync(target);
        return true;
    }

    /** Reads spawn.yml without touching Bukkit — the world may not exist yet at this point. */
    private Placed read() {
        String worldName = yaml.getString("world");
        if (worldName == null) return null;

        return new Placed(worldName,
                yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
    }

    private void write(Location location) {
        yaml.set("world", location.getWorld().getName());
        yaml.set("x", location.getX());
        yaml.set("y", location.getY());
        yaml.set("z", location.getZ());
        yaml.set("yaw", location.getYaw());
        yaml.set("pitch", location.getPitch());
        try {
            yaml.save(file);
        } catch (IOException e) {
            C.warning("Impossibile salvare spawn.yml: " + e.getMessage());
        }
        placed = new Placed(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        spawn = location;
        warnedMissingWorld = false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!teleportOnRespawn) return;
        Location target = spawn();
        if (target == null) return;
        if (!inLobbyWorld(event.getPlayer().getWorld())) return;
        event.setRespawnLocation(target);
    }

    /**
     * {@code /spawn} and {@code /setspawn}. An inner class rather than a top-level one because
     * both commands are only meaningful while this module is running, and the module's own
     * teardown is what unregisters them.
     */
    @CommandAlias("spawn|lobby|hub")
    public final class SpawnCommands extends BaseCommand {

        @Default
        @Description("Ti riporta allo spawn della lobby")
        @CommandCompletion("@players")
        public void onSpawn(Player sender, @Optional String targetName) {
            if (targetName != null && sender.hasPermission("lobbycore.spawn.others")) {
                Player target = Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    managers.messages().send(sender, "player-not-found",
                            Map.of("%player%", targetName));
                    return;
                }
                if (!send(target)) {
                    managers.messages().send(sender, "spawn-not-set");
                    return;
                }
                managers.messages().send(target, "spawn-teleported");
                managers.messages().send(sender, "spawn-teleported-other",
                        Map.of("%player%", target.getName()));
                return;
            }

            if (!send(sender)) {
                managers.messages().send(sender, "spawn-not-set");
                return;
            }
            managers.messages().send(sender, "spawn-teleported");
            Sounds.play(sender, config().getString("spawn.sound", "ENTITY_ENDERMAN_TELEPORT"),
                    1f, 1f);
        }

        @CommandAlias("setspawn")
        @CommandPermission("lobbycore.setspawn")
        @Description("Imposta lo spawn della lobby dove sei")
        public void onSetSpawn(Player sender) {
            write(sender.getLocation());
            managers.messages().send(sender, "spawn-set");
        }
    }
}
