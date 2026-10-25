package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The bus every lobby on the network shares: cross-server chat, the roster used for tab-complete
 * and network counts, and anything else a module wants to broadcast.
 *
 * <p>This is the RedisChat design generalised — instead of one hard-coded chat channel, modules
 * register a handler per channel through {@link #subscribe} and the single subscriber thread
 * fans messages out to them. One connection, any number of features.
 *
 * <p>Every write goes through a virtual-thread executor, so a Redis hiccup stalls a carrier
 * thread and not the server tick. The subscriber runs on its own virtual thread and reconnects
 * on its own; losing Redis degrades the lobby to local-only, it does not break it.
 */
public final class RedisManager implements Manager {

    private final LobbyCore plugin;

    private boolean enabled;
    private String serverName = "lobby-1";
    private String rosterKey = "lobbycore:players";

    private JedisPool pool;
    private JedisClientConfig clientConfig;
    private ExecutorService executor;
    private Thread subscriber;
    private volatile JedisPubSub activeSubscription;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private volatile Set<String> networkPlayers = Set.of();

    public RedisManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "redis"; }

    @Override
    public void enable() {
        YamlConfiguration config = plugin.getManagers().config().raw();
        serverName = plugin.getManagers().config().serverName();
        enabled = config.getBoolean("redis.enabled", false);
        rosterKey = config.getString("redis.roster-key", "lobbycore:players");

        if (!enabled) {
            C.info("Redis disattivato — la lobby gira in modalita locale.");
            return;
        }

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(config.getInt("redis.pool.max-total", 8));
            poolConfig.setMaxIdle(config.getInt("redis.pool.max-idle", 4));
            poolConfig.setMinIdle(config.getInt("redis.pool.min-idle", 1));
            poolConfig.setTestOnBorrow(true);
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setMaxWait(Duration.ofSeconds(3));

            DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(5000)
                    .socketTimeoutMillis(5000)
                    .ssl(config.getBoolean("redis.use-ssl", false));

            String password = config.getString("redis.password", "");
            if (password != null && !password.isEmpty()) builder.password(password);
            String user = config.getString("redis.username", "");
            if (user != null && !user.isEmpty()) builder.user(user);

            clientConfig = builder.build();
            pool = new JedisPool(poolConfig,
                    new HostAndPort(config.getString("redis.host", "127.0.0.1"),
                            config.getInt("redis.port", 6379)),
                    clientConfig);

            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }

            executor = Executors.newVirtualThreadPerTaskExecutor();
            running.set(true);
            subscriber = Thread.ofVirtual().name("LobbyCore-Redis").start(this::subscribeLoop);

            C.info("Redis connesso a &f" + config.getString("redis.host", "127.0.0.1")
                    + ":" + config.getInt("redis.port", 6379) + "&7 come &f" + serverName);
        } catch (Exception e) {
            enabled = false;
            closeQuietly();
            C.warning("Connessione a Redis fallita (" + e.getMessage() + ") — modalita locale.");
        }
    }

    @Override
    public void disable() {
        running.set(false);
        if (enabled) clearRoster();
        closeQuietly();
        handlers.clear();
        networkPlayers = Set.of();
    }

    private void closeQuietly() {
        if (activeSubscription != null) {
            try { activeSubscription.unsubscribe(); } catch (Exception ignored) {}
            activeSubscription = null;
        }
        if (subscriber != null) {
            subscriber.interrupt();
            subscriber = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (pool != null) {
            try { pool.close(); } catch (Exception ignored) {}
            pool = null;
        }
    }

    public boolean isEnabled() {
        return enabled && pool != null;
    }

    public String serverName() {
        return serverName;
    }

    // ------------------------------------------------------------------- pubsub

    /**
     * Registers a handler for one channel. The handler runs on the subscriber thread, so it must
     * not touch the Bukkit API directly — hop to the main thread for anything that does.
     *
     * <p>Registering after {@link #enable()} is fine and is the normal case: modules subscribe as
     * they start. The subscriber picks the new channel up on its next reconnect, so it is also
     * resubscribed explicitly here rather than waiting for one.
     */
    public void subscribe(String channel, Consumer<String> handler) {
        handlers.put(channel, handler);
        if (!isEnabled()) return;
        JedisPubSub subscription = activeSubscription;
        if (subscription != null && subscription.isSubscribed()) {
            try { subscription.subscribe(channel); } catch (Exception ignored) {}
        }
    }

    public void unsubscribe(String channel) {
        handlers.remove(channel);
        JedisPubSub subscription = activeSubscription;
        if (subscription != null && subscription.isSubscribed()) {
            try { subscription.unsubscribe(channel); } catch (Exception ignored) {}
        }
    }

    /** Fire-and-forget publish. Never blocks the caller. */
    public void publish(String channel, String payload) {
        if (!isEnabled()) return;
        executor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(channel, payload);
            } catch (Exception e) {
                if (plugin.getManagers().config().debug()) {
                    C.warning("publish su " + channel + " fallita: " + e.getMessage());
                }
            }
        });
    }

    private void subscribeLoop() {
        while (running.get()) {
            try (Jedis jedis = new Jedis(hostAndPort(), clientConfig)) {
                activeSubscription = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        Consumer<String> handler = handlers.get(channel);
                        if (handler == null) return;
                        try {
                            handler.accept(message);
                        } catch (Exception e) {
                            C.warning("Handler Redis su " + channel + " ha fallito: " + e.getMessage());
                        }
                    }
                };
                // The roster channel is always listened to; module channels join it.
                Set<String> channels = new HashSet<>(handlers.keySet());
                channels.add(rosterKey + ":updates");
                jedis.subscribe(activeSubscription, channels.toArray(String[]::new));
            } catch (Exception e) {
                if (!running.get()) return;
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private HostAndPort hostAndPort() {
        YamlConfiguration config = plugin.getManagers().config().raw();
        return new HostAndPort(config.getString("redis.host", "127.0.0.1"),
                config.getInt("redis.port", 6379));
    }

    // ------------------------------------------------------------------- roster

    /**
     * The names online on every <em>other</em> lobby, refreshed whenever one of them publishes a
     * change. Used for cross-server tab-complete and the network counter.
     */
    public Set<String> networkPlayers() {
        return networkPlayers;
    }

    public void addToRoster(String playerName) {
        rosterUpdate(jedis -> {
            var pipeline = jedis.pipelined();
            pipeline.sadd(localRosterKey(), playerName);
            pipeline.publish(rosterKey + ":updates", serverName);
            pipeline.sync();
        });
    }

    public void removeFromRoster(String playerName) {
        rosterUpdate(jedis -> {
            var pipeline = jedis.pipelined();
            pipeline.srem(localRosterKey(), playerName);
            pipeline.publish(rosterKey + ":updates", serverName);
            pipeline.sync();
        });
    }

    /** Rewrites this server's roster from scratch — on boot, and after a reconnect. */
    public void syncRoster() {
        if (!isEnabled()) return;
        String[] names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toArray(String[]::new);
        rosterUpdate(jedis -> {
            var pipeline = jedis.pipelined();
            pipeline.del(localRosterKey());
            if (names.length > 0) pipeline.sadd(localRosterKey(), names);
            pipeline.publish(rosterKey + ":updates", serverName);
            pipeline.sync();
        });
        refreshNetworkPlayers();
    }

    /**
     * Drops this server's roster. Called on shutdown — synchronously, because the executor is
     * about to be killed and a stale roster would leave ghosts in every other lobby's
     * tab-complete until someone restarts them.
     */
    public void clearRoster() {
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            var pipeline = jedis.pipelined();
            pipeline.del(localRosterKey());
            pipeline.publish(rosterKey + ":updates", serverName);
            pipeline.sync();
        } catch (Exception ignored) {
        }
    }

    /** Re-reads every other server's roster. Runs off-thread; called from the subscriber. */
    public void refreshNetworkPlayers() {
        if (!isEnabled()) return;
        executor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                Set<String> all = new HashSet<>();
                String local = localRosterKey();
                String updates = rosterKey + ":updates";
                // SCAN rather than KEYS: KEYS blocks the Redis server for the whole keyspace,
                // and this runs every time anyone on the network joins or leaves.
                ScanParams params = new ScanParams().match(rosterKey + ":*").count(100);
                String cursor = "0";
                do {
                    ScanResult<String> result = jedis.scan(cursor, params);
                    for (String key : result.getResult()) {
                        if (key.equals(local) || key.equals(updates)) continue;
                        all.addAll(jedis.smembers(key));
                    }
                    cursor = result.getCursor();
                } while (!"0".equals(cursor));
                networkPlayers = Collections.unmodifiableSet(all);
            } catch (Exception ignored) {
            }
        });
    }

    private String localRosterKey() {
        return rosterKey + ":" + serverName;
    }

    private void rosterUpdate(Consumer<Jedis> action) {
        if (!isEnabled()) return;
        executor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                action.accept(jedis);
            } catch (Exception ignored) {
            }
        });
    }

    /** Wires the roster channel to the refresh, once the manager is up. */
    public void startRosterSync() {
        if (!isEnabled()) return;
        subscribe(rosterKey + ":updates", message -> {
            if (!serverName.equals(message)) refreshNetworkPlayers();
        });
        syncRoster();
    }
}
