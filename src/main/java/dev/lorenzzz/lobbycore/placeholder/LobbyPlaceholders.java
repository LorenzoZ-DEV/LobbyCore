package dev.lorenzzz.lobbycore.placeholder;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.PlayerDataManager;
import dev.lorenzzz.lobbycore.module.modules.blocks.LobbyBlocksModule;
import dev.lorenzzz.lobbycore.module.modules.pvp.HubPvpModule;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;

/**
 * The {@code %lobbycore_…%} expansion: the numbers a scoreboard or tab design wants that no other
 * plugin knows — network head-count from the Redis roster, lobby PvP tallies, playtime.
 *
 * <p>Registered directly rather than shipped as a separate expansion jar, so there is nothing to
 * install and the values always match the running build.
 */
public final class LobbyPlaceholders extends PlaceholderExpansion {

    private final LobbyCore plugin;

    public LobbyPlaceholders(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "lobbycore"; }
    @Override public String getAuthor() { return "Lorenzzz"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }

    /** The plugin owns its own lifecycle, so PlaceholderAPI must not unregister us on reload. */
    @Override public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        var managers = plugin.getManagers();
        String key = params.toLowerCase(Locale.ROOT);

        switch (key) {
            case "server" -> {
                return managers.config().serverName();
            }
            case "online" -> {
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            }
            case "network_online" -> {
                // Local players plus everybody the Redis roster knows about elsewhere.
                return String.valueOf(Bukkit.getOnlinePlayers().size()
                        + managers.redis().networkPlayers().size());
            }
            case "network_elsewhere" -> {
                return String.valueOf(managers.redis().networkPlayers().size());
            }
            case "modules_running" -> {
                return String.valueOf(managers.modules().runningCount());
            }
            case "pvp_players" -> {
                HubPvpModule pvp = managers.modules().module("hub-pvp", HubPvpModule.class);
                return String.valueOf(pvp == null ? 0 : pvp.activeCount());
            }
            case "blocks_active" -> {
                LobbyBlocksModule blocks = managers.modules()
                        .module("lobby-blocks", LobbyBlocksModule.class);
                return String.valueOf(blocks == null ? 0 : blocks.activeBlocks());
            }
            default -> { }
        }

        if (player == null) return "";
        PlayerDataManager.LobbyPlayer data = managers.playerData().get(player);
        if (data == null) return "";

        return switch (key) {
            case "kills" -> String.valueOf(data.kills());
            case "deaths" -> String.valueOf(data.deaths());
            case "kdr" -> kdr(data);
            case "hidden" -> data.playersHidden() ? "si" : "no";
            case "playtime" -> formatDuration(data.playtime());
            case "playtime_hours" -> String.valueOf(Duration.ofMillis(data.playtime()).toHours());
            case "pvp_active" -> {
                HubPvpModule pvp = managers.modules().module("hub-pvp", HubPvpModule.class);
                yield pvp != null && pvp.isActive(player) ? "si" : "no";
            }
            default -> null;
        };
    }

    /** Deaths of zero is a ratio with no denominator; showing the kill count is the convention. */
    private static String kdr(PlayerDataManager.LobbyPlayer data) {
        if (data.deaths() == 0) return String.format(Locale.ROOT, "%.2f", (double) data.kills());
        return String.format(Locale.ROOT, "%.2f", (double) data.kills() / data.deaths());
    }

    private static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
