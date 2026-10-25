package dev.lorenzzz.lobbycore.module.modules.web;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.PlayerDataManager;
import dev.lorenzzz.lobbycore.module.Module;
import dev.lorenzzz.lobbycore.module.modules.pvp.HubPvpModule;
import dev.lorenzzz.lobbycore.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The once-a-second snapshot: server health, module states, and who is online with what they are
 * doing right now.
 *
 * <p>The player-count history is kept here rather than in a module so the chart survives a module
 * reload — an admin toggling something from the panel should not watch the graph reset.
 */
final class WebStats {

    /** More points than this is invisible at chart width. Roughly an hour at one sample/20s. */
    private static final int HISTORY_POINTS = 180;
    private static final long HISTORY_INTERVAL_MILLIS = 20_000L;

    private final LobbyCore plugin;
    private final long startedAt;

    /** [timestamp, online] pairs, oldest first. */
    private final Deque<long[]> history = new ArrayDeque<>();
    private long lastSample;
    private int peakOnline;
    private long peakAt;

    WebStats(LobbyCore plugin, long startedAt) {
        this.plugin = plugin;
        this.startedAt = startedAt;
    }

    /** Called once a second by the broadcaster; records a history point at its own interval. */
    void sample() {
        long now = System.currentTimeMillis();
        int online = Bukkit.getOnlinePlayers().size();

        if (online > peakOnline) {
            peakOnline = online;
            peakAt = now;
        }
        if (now - lastSample < HISTORY_INTERVAL_MILLIS) return;

        lastSample = now;
        history.addLast(new long[] { now, online });
        while (history.size() > HISTORY_POINTS) history.pollFirst();
    }

    String snapshot() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long used = total - runtime.freeMemory();

        double[] tps = { 20, 20, 20 };
        try {
            double[] live = Bukkit.getTPS();
            if (live != null && live.length >= 3) tps = live;
        } catch (Throwable ignored) {
            // Not a Paper server, or TPS not available yet.
        }
        final double[] rates = tps;

        var managers = plugin.getManagers();

        return Json.object(json -> {
            json.num("now", System.currentTimeMillis());
            json.num("uptime", System.currentTimeMillis() - startedAt);
            json.str("server", Bukkit.getVersion());
            json.str("lobby", managers.config().serverName());
            json.str("plugin", plugin.getPluginMeta().getVersion());

            json.obj("tps", t -> t.num("m1", rates[0]).num("m5", rates[1]).num("m15", rates[2]));
            json.obj("heap", h -> h
                    .num("used", used).num("total", total).num("max", runtime.maxMemory()));
            json.obj("threads", t -> t
                    .num("count", threads.getThreadCount())
                    .num("daemon", threads.getDaemonThreadCount()));

            json.obj("players", p -> {
                p.num("online", Bukkit.getOnlinePlayers().size());
                p.num("max", Bukkit.getMaxPlayers());
                p.num("network", Bukkit.getOnlinePlayers().size()
                        + managers.redis().networkPlayers().size());
                p.num("peak", peakOnline);
                p.num("peakAt", peakAt);
            });

            json.obj("services", s -> s
                    .bool("redis", managers.redis().isEnabled())
                    .bool("database", managers.storage().available())
                    .bool("luckperms", managers.hooks().hasLuckPerms())
                    .bool("placeholderapi", dev.lorenzzz.lobbycore.C.papiEnabled()));

            json.raw("modules", modulesJson());
            json.obj("counters", c -> c
                    .num("errors", managers.errors().count())
                    .num("severe", managers.errors().severeCount())
                    .num("activity", managers.activity().size()));
        });
    }

    /** Every module with its configured flag and whether it is actually running. */
    private String modulesJson() {
        var modules = plugin.getManagers().modules();
        var config = plugin.getManagers().config();
        List<Module> all = new ArrayList<>(modules.all());

        return Json.array(all, (writer, module) -> writer.obj(json -> json
                .str("id", module.id())
                .str("description", module.description())
                .str("file", module.configFile() + ".yml")
                .bool("enabled", config.isEnabled(module.id()))
                .bool("running", modules.isRunning(module.id()))));
    }

    /** Who is online, with the details a staffer actually wants next to a name. */
    String playersJson() {
        var managers = plugin.getManagers();
        HubPvpModule pvp = managers.modules().module("hub-pvp", HubPvpModule.class);
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());

        return Json.array(online, (writer, player) -> {
            PlayerDataManager.LobbyPlayer data = managers.playerData().get(player);
            var address = player.getAddress();

            writer.obj(json -> {
                json.str("name", player.getName());
                json.str("uuid", player.getUniqueId().toString());
                json.str("world", player.getWorld().getName());
                json.num("ping", player.getPing());
                json.str("gamemode", player.getGameMode().name().toLowerCase());
                json.str("address", address == null ? "" : address.getHostString());
                json.bool("pvp", pvp != null && pvp.isActive(player));
                json.str("group", managers.hooks().hasLuckPerms()
                        ? managers.hooks().luckPerms().primaryGroup(player) : "");
                if (data == null) {
                    json.num("kills", 0).num("deaths", 0).num("playtime", 0).bool("hidden", false);
                } else {
                    json.num("kills", data.kills());
                    json.num("deaths", data.deaths());
                    json.num("playtime", data.playtime());
                    json.bool("hidden", data.playersHidden());
                    json.num("firstJoin", data.firstJoin());
                }
            });
        });
    }

    /** The player-count chart, as {@code [[timestamp, online], …]}. */
    String historyJson() {
        return Json.array(new ArrayList<>(history), (writer, point) ->
                writer.obj(json -> json.num("t", point[0]).num("n", point[1])));
    }
}
