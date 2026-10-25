package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.Module;
import dev.lorenzzz.lobbycore.module.modules.announcement.AnnouncementModule;
import dev.lorenzzz.lobbycore.module.modules.blocks.LobbyBlocksModule;
import dev.lorenzzz.lobbycore.module.modules.bow.TeleportBowModule;
import dev.lorenzzz.lobbycore.module.modules.chat.ChatFilterModule;
import dev.lorenzzz.lobbycore.module.modules.chat.ChatModule;
import dev.lorenzzz.lobbycore.module.modules.hotbar.HotbarModule;
import dev.lorenzzz.lobbycore.module.modules.hotbar.PlayerHiderModule;
import dev.lorenzzz.lobbycore.module.modules.join.JoinModule;
import dev.lorenzzz.lobbycore.module.modules.pvp.HubPvpModule;
import dev.lorenzzz.lobbycore.module.modules.scoreboard.ScoreboardModule;
import dev.lorenzzz.lobbycore.module.modules.spawn.SpawnModule;
import dev.lorenzzz.lobbycore.module.modules.tab.TablistModule;
import dev.lorenzzz.lobbycore.module.modules.web.WebModule;
import dev.lorenzzz.lobbycore.module.modules.world.AntiWdlModule;
import dev.lorenzzz.lobbycore.module.modules.world.DisabledWorldsModule;
import dev.lorenzzz.lobbycore.module.modules.world.DoubleJumpModule;
import dev.lorenzzz.lobbycore.module.modules.world.LaunchpadModule;
import dev.lorenzzz.lobbycore.module.modules.world.WorldSettingsModule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Registry and lifecycle for features. Every module is constructed at boot but only the ones
 * flagged on under {@code modules:} in config.yml are started.
 *
 * <p>Registration order is start-up order and its reverse is shutdown order, which is why the
 * list below is not alphabetical: the hotbar has to be up before the join module starts handing
 * items out, and the hider has to be up before the hotbar gives anyone its toggle.
 */
public final class ModuleManager implements Manager {

    private final LobbyCore plugin;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    private ManagerService managers;
    private ConfigManager config;

    public ModuleManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "modules"; }

    @Override
    public void enable() {
        managers = plugin.getManagers();
        config = managers.config();

        register(new WorldSettingsModule(plugin));
        register(new DisabledWorldsModule(plugin));
        register(new SpawnModule(plugin));
        register(new PlayerHiderModule(plugin));
        register(new HotbarModule(plugin));
        register(new JoinModule(plugin));
        register(new DoubleJumpModule(plugin));
        register(new LaunchpadModule(plugin));
        register(new AntiWdlModule(plugin));
        register(new LobbyBlocksModule(plugin));
        register(new TeleportBowModule(plugin));
        register(new HubPvpModule(plugin));
        register(new ChatModule(plugin));
        register(new ChatFilterModule(plugin));
        register(new AnnouncementModule(plugin));
        register(new ScoreboardModule(plugin));
        register(new TablistModule(plugin));
        // Last: the panel is a view over everything above it, so by the time it serves its
        // first request every module it reports on already exists.
        register(new WebModule(plugin));

        startEnabled();
    }

    public ModuleManager register(Module module) {
        modules.put(module.id(), module);
        return this;
    }

    private void startEnabled() {
        startEnabled(Set.of());
    }

    private void startEnabled(Set<String> skip) {
        for (String id : modules.keySet()) {
            if (!skip.contains(id) && config.isEnabled(id)) start(id);
        }
    }

    /**
     * Stops everything running and starts whatever is flagged on now. Used by
     * {@code /lobbycore reload} after the configs have been re-read.
     */
    public void reload() {
        reload(Set.of());
    }

    /**
     * Same as {@link #reload()}, but leaves the named modules exactly as they are.
     *
     * <p>The web panel needs this: it is itself a module, and a blanket reload would stop and
     * restart the HTTP server that is still serving the request asking for the reload — dropping
     * the event stream and invalidating the caller's session mid-flight.
     */
    public void reload(Set<String> skip) {
        for (String id : List.copyOf(running)) {
            if (!skip.contains(id)) stop(id);
        }
        // bind() re-reads each module's file, so a module that stayed enabled still picks up its
        // edited config rather than keeping the copy it read at boot.
        startEnabled(skip);
    }

    /**
     * Starts or stops one module in place. Returns {@code null} on success, otherwise a line fit
     * to show whoever asked.
     */
    public String setEnabled(String id, boolean enabled) {
        if (!modules.containsKey(id)) return "modulo '" + id + "' inesistente";
        if (!enabled) {
            if (!running.contains(id)) return "il modulo '" + id + "' e gia spento";
            stop(id);
            return null;
        }
        if (running.contains(id)) return "il modulo '" + id + "' e gia acceso";
        return start(id) ? null : "il modulo '" + id + "' ha fallito l'avvio — guarda la console";
    }

    private boolean start(String id) {
        Module module = modules.get(id);
        if (module == null || running.contains(id)) return module != null;
        try {
            module.bind(managers);
            module.onEnable();
            running.add(id);
            if (config.debug()) C.info("Modulo '&f" + id + "&7' avviato.");
            return true;
        } catch (Exception e) {
            C.warning("Errore avviando il modulo '" + id + "': " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "Module '" + id + "' failed to start", e);
            return false;
        }
    }

    /** Stops one module, but only if it actually started — onDisable must never run unpaired. */
    private void stop(String id) {
        Module module = modules.get(id);
        if (module == null || !running.remove(id)) return;
        try {
            module.onDisable();
        } catch (Exception e) {
            C.warning("Errore fermando il modulo '" + id + "': " + e.getMessage());
        }
    }

    public boolean isRunning(String id) {
        return running.contains(id);
    }

    public int runningCount() {
        return running.size();
    }

    public List<String> ids() {
        return new ArrayList<>(modules.keySet());
    }

    public Collection<Module> all() {
        return modules.values();
    }

    public Module module(String id) {
        return modules.get(id);
    }

    /** Lookup by id and type in one step; {@code null} when absent, stopped, or another type. */
    public <T extends Module> T module(String id, Class<T> type) {
        Module module = modules.get(id);
        return type.isInstance(module) && running.contains(id) ? type.cast(module) : null;
    }

    @Override
    public void disable() {
        // Reverse of start-up order: a module that leaned on an earlier one during start-up gets
        // to clean up before that one goes away. The order comes from the registration map, not
        // from `running` — that one is a concurrent set and has no order to reverse.
        List<String> ordered = new ArrayList<>(modules.keySet());
        for (int index = ordered.size() - 1; index >= 0; index--) {
            stop(ordered.get(index));
        }
        running.clear();
        modules.clear();
    }
}
