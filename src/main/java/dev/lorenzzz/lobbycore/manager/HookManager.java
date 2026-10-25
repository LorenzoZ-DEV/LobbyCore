package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.hook.LuckPermsHook;
import dev.lorenzzz.lobbycore.placeholder.LobbyPlaceholders;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Optional third-party integrations, resolved once at boot. Everything here is soft: a lobby
 * without PlaceholderAPI or LuckPerms starts fine, it just renders fewer things.
 */
public final class HookManager implements Manager, Listener {

    private final LobbyCore plugin;
    private LuckPermsHook luckPerms;
    private LobbyPlaceholders placeholders;

    public HookManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "hooks"; }

    @Override
    public void enable() {
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        C.papi(papi);
        if (papi) {
            placeholders = new LobbyPlaceholders(plugin);
            placeholders.register();
            C.info("Agganciato a &fPlaceholderAPI&7 (espansione &f%lobbycore_…%&7 registrata).");
        } else {
            C.info("PlaceholderAPI non presente — i placeholder esterni non verranno risolti.");
        }

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            luckPerms = LuckPermsHook.tryCreate();
            if (luckPerms != null) {
                C.info("Agganciato a &fLuckPerms&7.");
                Bukkit.getPluginManager().registerEvents(this, plugin);
            }
        }
    }

    /**
     * Drops a leaver's cached prefix and weight.
     *
     * <p>The cache expires on its own after half a minute, so this is hygiene rather than
     * correctness — but it belongs here, next to the cache, and not in whichever module happened
     * to already have a quit handler.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (luckPerms != null) luckPerms.invalidate(event.getPlayer().getUniqueId());
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (placeholders != null) {
            placeholders.unregister();
            placeholders = null;
        }
        luckPerms = null;
    }

    /** {@code null} when LuckPerms is not installed — every caller must handle that. */
    public LuckPermsHook luckPerms() {
        return luckPerms;
    }

    public boolean hasLuckPerms() {
        return luckPerms != null;
    }
}
