package dev.lorenzzz.lobbycore.module;

import co.aikar.commands.BaseCommand;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ManagerService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for every module: holds the managers, its own config file, and — the part that matters —
 * a record of everything it registered with the server.
 *
 * <p>Listeners, tasks and commands registered through the helpers here are all taken back in
 * {@link #onDisable()}. Modules that hand these out themselves are the usual reason a
 * "reload" command doubles every join message; doing the bookkeeping in one place means no
 * module can forget.
 */
public abstract class AbstractModule implements Module {

    protected final LobbyCore plugin;
    protected ManagerService managers;

    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<BaseCommand> commands = new ArrayList<>();

    private YamlConfiguration config;

    protected AbstractModule(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void bind(ManagerService managers) {
        this.managers = managers;
        this.config = managers.config().module(configFile());
    }

    /** This module's own yml, already merged with the bundled defaults. */
    protected YamlConfiguration config() {
        return config;
    }

    /** A section of this module's file, or an empty one so callers never null-check. */
    protected ConfigurationSection section(String path) {
        ConfigurationSection found = config.getConfigurationSection(path);
        return found != null ? found : config.createSection(path);
    }

    /** Whether this module should act in the given world — see the disabled-worlds module. */
    protected boolean inLobbyWorld(org.bukkit.World world) {
        return managers.isLobbyWorld(world);
    }

    protected boolean inLobbyWorld(org.bukkit.entity.Entity entity) {
        return managers.isLobbyWorld(entity.getWorld());
    }

    // ------------------------------------------------------------- registration

    protected void listen(Listener... toRegister) {
        for (Listener listener : toRegister) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            listeners.add(listener);
        }
    }

    protected void repeat(Runnable action, long delayTicks, long periodTicks) {
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, action, delayTicks, periodTicks));
    }

    protected void repeatAsync(Runnable action, long delayTicks, long periodTicks) {
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, action, delayTicks, periodTicks));
    }

    protected void later(Runnable action, long delayTicks) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, action, delayTicks));
    }

    protected void command(BaseCommand... toRegister) {
        for (BaseCommand base : toRegister) {
            managers.commands().register(base);
            commands.add(base);
        }
    }

    /**
     * Gives back every listener, task and command this module took. Subclasses override
     * {@link #onDisable()} and must call {@code super.onDisable()} — or better, override
     * {@link #shutdown()} and leave this one alone.
     */
    @Override
    public void onDisable() {
        shutdown();
        for (Listener listener : listeners) HandlerList.unregisterAll(listener);
        listeners.clear();

        for (BukkitTask task : tasks) {
            if (!task.isCancelled()) task.cancel();
        }
        tasks.clear();

        if (!commands.isEmpty()) {
            managers.commands().unregister(commands.toArray(BaseCommand[]::new));
            commands.clear();
        }
    }

    /** Module-specific teardown — restoring player state, flushing caches. */
    protected void shutdown() {}
}
