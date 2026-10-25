package dev.lorenzzz.lobbycore.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The per-player state modules read on the hot path: hider preference, PvP tally, playtime.
 *
 * <p>Caffeine sits in front of the database because these are read far more often than they are
 * written — the hider module asks "is this player hiding?" on every join of every other player,
 * and a scoreboard placeholder asks for kills once a second. Loading happens on the pre-login
 * thread, which is the one moment we are allowed to block on I/O without costing a tick.
 *
 * <p>Entries expire ten minutes after the player leaves rather than on quit, so a reconnect —
 * the common case in a lobby, where players bounce between servers — usually hits the cache.
 * Writes are queued to the async scheduler; nothing here touches JDBC on the main thread.
 */
public final class PlayerDataManager implements Manager, Listener {

    /** One player's row, mutable in place: the cache holds the object, modules mutate it. */
    public static final class LobbyPlayer {
        private final UUID uuid;
        private volatile String name;
        private volatile long firstJoin;
        private volatile long lastJoin;
        private volatile long playtime;
        private volatile boolean playersHidden;
        private volatile int kills;
        private volatile int deaths;
        private volatile boolean dirty;
        /** Session start in millis, folded into {@link #playtime} when the player leaves. */
        private volatile long sessionStart;

        LobbyPlayer(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public long firstJoin() { return firstJoin; }
        public long lastJoin() { return lastJoin; }
        public boolean playersHidden() { return playersHidden; }
        public int kills() { return kills; }
        public int deaths() { return deaths; }

        /** Stored playtime plus the part of the current session not yet folded in. */
        public long playtime() {
            long live = sessionStart == 0 ? 0 : System.currentTimeMillis() - sessionStart;
            return playtime + live;
        }

        /** Whether this is the player's first ever join to the lobby. */
        public boolean firstJoinEver() {
            return firstJoin == lastJoin;
        }

        public void playersHidden(boolean hidden) { this.playersHidden = hidden; dirty = true; }
        public void addKill() { kills++; dirty = true; }
        public void addDeath() { deaths++; dirty = true; }

        void closeSession() {
            if (sessionStart != 0) {
                playtime += System.currentTimeMillis() - sessionStart;
                sessionStart = 0;
                dirty = true;
            }
        }
    }

    private final LobbyCore plugin;
    private StorageManager storage;
    private BukkitTask flushTask;

    private final Cache<UUID, LobbyPlayer> cache = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .maximumSize(1024)
            .build();

    public PlayerDataManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "player-data"; }

    @Override
    public void enable() {
        storage = plugin.getManagers().storage();
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Anyone already online when the plugin (re)loads has no cache entry and no session.
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> load(player.getUniqueId(), player.getName()));
        }

        // Periodic flush: a server that dies without a clean shutdown otherwise loses every
        // stat change since the last quit.
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, 6000L, 6000L);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        // Synchronous on purpose: the scheduler is already gone by the time onDisable runs, so
        // an async save would never execute.
        for (LobbyPlayer data : List.copyOf(cache.asMap().values())) {
            data.closeSession();
            save(data);
        }
        cache.invalidateAll();
    }

    /** The cached row, or {@code null} when the player has not been loaded. */
    public LobbyPlayer get(UUID uuid) {
        return cache.getIfPresent(uuid);
    }

    public LobbyPlayer get(Player player) {
        return get(player.getUniqueId());
    }

    /**
     * The cached row, loading it synchronously if absent. Only for command paths, where one
     * blocking read is acceptable and returning null is not.
     */
    public LobbyPlayer getOrLoad(UUID uuid, String name) {
        LobbyPlayer data = cache.getIfPresent(uuid);
        return data != null ? data : load(uuid, name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        // Already off the main thread, and the player is not in the world yet — the one place
        // a blocking read costs nothing.
        load(event.getUniqueId(), event.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        LobbyPlayer data = cache.getIfPresent(event.getPlayer().getUniqueId());
        if (data == null) return;
        data.closeSession();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> save(data));
    }

    private LobbyPlayer load(UUID uuid, String name) {
        LobbyPlayer data = new LobbyPlayer(uuid, name);
        long now = System.currentTimeMillis();

        if (storage.available()) {
            try (Connection connection = storage.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM " + storage.table("players") + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        data.firstJoin = rs.getLong("first_join");
                        data.playtime = rs.getLong("playtime");
                        data.playersHidden = rs.getBoolean("players_hidden");
                        data.kills = rs.getInt("pvp_kills");
                        data.deaths = rs.getInt("pvp_deaths");
                    }
                }
            } catch (SQLException e) {
                C.warning("Impossibile leggere i dati di " + name + ": " + e.getMessage());
            }
        }

        if (data.firstJoin == 0) data.firstJoin = now;
        data.lastJoin = now;
        data.sessionStart = now;
        data.name = name;
        data.dirty = true;

        cache.put(uuid, data);
        return data;
    }

    private void save(LobbyPlayer data) {
        if (!data.dirty || !storage.available()) return;

        // The two backends do not share an upsert: SQLite (and Postgres) spell it
        // ON CONFLICT … DO UPDATE with an `excluded` pseudo-table, MySQL and MariaDB spell it
        // ON DUPLICATE KEY UPDATE with VALUES(). The VALUES() form is deprecated on MySQL 8.0.20+
        // but still works, and unlike the `AS new` alias that replaces it, MariaDB and MySQL 5.7
        // understand it too.
        String insert = "INSERT INTO " + storage.table("players")
                + " (uuid, name, first_join, last_join, playtime, players_hidden, pvp_kills, pvp_deaths)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ";
        String sql = storage.isSqlite()
                ? insert + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name,"
                + " last_join = excluded.last_join, playtime = excluded.playtime,"
                + " players_hidden = excluded.players_hidden, pvp_kills = excluded.pvp_kills,"
                + " pvp_deaths = excluded.pvp_deaths"
                : insert + "ON DUPLICATE KEY UPDATE name = VALUES(name),"
                + " last_join = VALUES(last_join), playtime = VALUES(playtime),"
                + " players_hidden = VALUES(players_hidden), pvp_kills = VALUES(pvp_kills),"
                + " pvp_deaths = VALUES(pvp_deaths)";

        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, data.uuid.toString());
            statement.setString(2, data.name);
            statement.setLong(3, data.firstJoin);
            statement.setLong(4, data.lastJoin);
            statement.setLong(5, data.playtime);
            statement.setBoolean(6, data.playersHidden);
            statement.setInt(7, data.kills);
            statement.setInt(8, data.deaths);
            statement.executeUpdate();
            data.dirty = false;
        } catch (SQLException e) {
            C.warning("Impossibile salvare i dati di " + data.name + ": " + e.getMessage());
        }
    }

    private void flushAll() {
        for (LobbyPlayer data : List.copyOf(cache.asMap().values())) {
            save(data);
        }
    }
}
