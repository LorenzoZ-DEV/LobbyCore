package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.action.ActionManager;
import dev.lorenzzz.lobbycore.menu.MenuManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Owns every manager, starts them in dependency order and stops them in reverse.
 *
 * <p>The order below is the dependency graph written down: config feeds everything, storage
 * backs the player cache, hooks must resolve PlaceholderAPI before any module renders a line,
 * and modules go last because they use all of it.
 */
public final class ManagerService implements Manager {

    private final LobbyCore plugin;
    /** Insertion order IS the start order; the reverse is the shutdown order. */
    private final Map<Class<?>, Manager> managers = new LinkedHashMap<>();

    private ConfigManager config;
    private ErrorCapture errors;
    private MessagesManager messages;
    private StorageManager storage;
    private PlayerDataManager playerData;
    private ActivityLog activity;
    private RedisManager redis;
    private HookManager hooks;
    private ActionManager actions;
    private MenuManager menus;
    private CommandManager commands;
    private ModuleManager modules;

    public ManagerService(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "manager-service"; }

    @Override
    public void enable() {
        config = register(new ConfigManager(plugin));
        // Second, right after config: it attaches a log handler, and everything that could fail
        // during the rest of start-up is exactly what it exists to catch.
        errors = register(new ErrorCapture(plugin));
        messages = register(new MessagesManager(plugin));
        storage = register(new StorageManager(plugin));
        playerData = register(new PlayerDataManager(plugin));
        // After playerData: the quit handler reports the session length it holds.
        activity = register(new ActivityLog(plugin));
        redis = register(new RedisManager(plugin));
        // Before actions and menus: both render text, and C has to know whether PlaceholderAPI
        // is there before the first line is parsed and cached.
        hooks = register(new HookManager(plugin));
        actions = register(new ActionManager(plugin));
        menus = register(new MenuManager(plugin));
        commands = register(new CommandManager(plugin));
        modules = register(new ModuleManager(plugin));

        for (Manager manager : managers.values()) {
            try {
                manager.enable();
            } catch (Exception e) {
                C.error("Avvio di " + manager.name() + " fallito: " + e);
                plugin.getLogger().log(Level.SEVERE, "Manager start-up failure", e);
            }
        }

        // After every manager is up: the roster sync publishes to Redis and reads the online
        // list, neither of which is meaningful earlier.
        redis.startRosterSync();
    }

    /**
     * Re-reads the configs and restarts every module against them.
     *
     * <p>Storage and Redis are deliberately left alone: both hold live connections, and tearing
     * a pool down mid-session to re-read a host name that almost never changes costs more than
     * it is worth. A database or Redis change needs a server restart, and the reload command
     * says so.
     */
    @Override
    public void reload() {
        config.reload();
        errors.reload();
        activity.reload();
        messages.reload();
        menus.reload();
        modules.reload();
    }

    /**
     * Which worlds the lobby modules act in.
     *
     * <p>This is the one genuinely cross-cutting setting: world protection, the hotbar, double
     * jump and the scoreboard all have to agree on where they apply, and a lobby that also hosts
     * a build world wants them off in it. The disabled-worlds module installs the real filter
     * when it starts and takes it back when it stops, so nothing has to null-check a module.
     */
    private volatile java.util.function.Predicate<org.bukkit.World> worldFilter = world -> true;

    public boolean isLobbyWorld(org.bukkit.World world) {
        return world != null && worldFilter.test(world);
    }

    public void worldFilter(java.util.function.Predicate<org.bukkit.World> filter) {
        this.worldFilter = filter == null ? world -> true : filter;
    }

    private <T extends Manager> T register(T manager) {
        managers.put(manager.getClass(), manager);
        return manager;
    }

    @Override
    public void disable() {
        List<Manager> ordered = new ArrayList<>(managers.values());
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Manager manager = ordered.get(index);
            try {
                manager.disable();
            } catch (Exception e) {
                C.warning("Errore fermando " + manager.name() + ": " + e);
            }
        }
        managers.clear();
    }

    @SuppressWarnings("unchecked")
    public <T extends Manager> T get(Class<T> type) {
        return (T) managers.get(type);
    }

    public ConfigManager config() { return config; }
    public ErrorCapture errors() { return errors; }
    public MessagesManager messages() { return messages; }
    public StorageManager storage() { return storage; }
    public PlayerDataManager playerData() { return playerData; }
    public ActivityLog activity() { return activity; }
    public RedisManager redis() { return redis; }
    public HookManager hooks() { return hooks; }
    public ActionManager actions() { return actions; }
    public MenuManager menus() { return menus; }
    public CommandManager commands() { return commands; }
    public ModuleManager modules() { return modules; }
}
