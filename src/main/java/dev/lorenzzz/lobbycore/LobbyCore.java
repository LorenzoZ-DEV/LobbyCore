package dev.lorenzzz.lobbycore;

import dev.lorenzzz.lobbycore.manager.ManagerService;
import dev.lorenzzz.lobbycore.manager.ModuleManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LobbyCore — the single plugin the VenrixMC lobbies run.
 *
 * <p>It replaces a stack that used to be separate jars (lobby-utils, nLobbyBlocks, l-teleportbow,
 * a-hubpvp, RedisChat) plus a DeluxeHub-shaped config layer. Merging them is not tidiness for its
 * own sake: they fought over the same hotbar slots, each shipped its own copy of ACF and its own
 * config-merge code, and three of them registered a PlayerJoinEvent handler that gave the player
 * an item without knowing what the other two had already put there. One plugin means one hotbar
 * owner, one text pipeline, one connection pool and one reload.
 */
public final class LobbyCore extends JavaPlugin {

    private static LobbyCore instance;

    private ManagerService managers;

    @Override
    public void onLoad() {
        C.logLoad();
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        // The existence check is Bukkit's job in theory; in practice saveResource logs a WARNING
        // every start-up once the file is there, which reads like something went wrong.
        if (!new java.io.File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }

        // The proxy switch action writes on this channel; without it [PROXY] silently does
        // nothing and nobody can leave the lobby through a selector.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        managers = new ManagerService(this);
        managers.enable();

        C.line();
        C.logStart(managers.modules().runningCount());
    }

    @Override
    public void onDisable() {
        if (managers != null) {
            managers.disable();
            managers = null;
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        C.logStop();
        instance = null;
    }

    public static LobbyCore getInstance() {
        return instance;
    }

    public ManagerService getManagers() {
        return managers;
    }

    /** Shortcut for the module registry; {@code null} before enable and after disable. */
    public ModuleManager modules() {
        return managers == null ? null : managers.modules();
    }

    /** Re-reads configs and restarts the modules. Returns the number left running. */
    public int reloadEverything() {
        reloadConfig();
        managers.reload();
        return managers.modules().runningCount();
    }
}
