package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What players actually did: joined, clicked the compass, opened the selector, toggled the hider,
 * entered PvP, killed somebody, ran a command.
 *
 * <p>A manager rather than a part of the web module on purpose. The panel is a <em>view</em> of
 * this, not its owner — so the history is still being recorded when nobody has the browser open,
 * {@code /lobbycore activity <player>} works on a server with the panel switched off, and no
 * module has to look up whether a dashboard exists before it can report something.
 *
 * <p>It is a bounded ring in memory and nothing more. Persisting it would mean a write per click
 * per player, which is a lot of I/O for data whose whole value is being recent; an admin asking
 * "what did they just do" is served by the last few hundred entries, and anything older belongs
 * in a real audit system.
 *
 * <p>Every event type can be switched off individually. The noisy ones — block placement, bow
 * teleports — ship off, because in a full lobby they would push everything else out of the ring
 * within seconds.
 */
public final class ActivityLog implements Manager, Listener {

    /** One thing that happened. {@code player} is null for server-side and panel events. */
    public record Entry(long time, String type, String player, String detail) {}

    // Event type ids. Kept as constants rather than an enum so a module can report a type this
    // class has never heard of without a recompile — the panel renders whatever it is given.
    public static final String JOIN = "join";
    public static final String QUIT = "quit";
    public static final String COMMAND = "command";
    public static final String CHAT = "chat";
    public static final String MENU = "menu";
    public static final String HOTBAR = "hotbar";
    public static final String HIDER = "hider";
    public static final String PVP = "pvp";
    public static final String KILL = "kill";
    public static final String BLOCK = "block";
    public static final String BOW = "bow";
    public static final String SWITCH = "switch";
    public static final String BLOCKED = "blocked";
    public static final String PANEL = "panel";

    private final LobbyCore plugin;

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicLong version = new AtomicLong();
    private final Map<String, Boolean> tracked = new ConcurrentHashMap<>();

    private int capacity = 500;
    private volatile boolean enabled = true;

    public ActivityLog(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "activity"; }

    @Override
    public void enable() {
        readConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        entries.clear();
    }

    @Override
    public void reload() {
        readConfig();
        trim();
    }

    private void readConfig() {
        var config = plugin.getManagers().config().raw();
        enabled = config.getBoolean("activity.enabled", true);
        capacity = Math.max(50, Math.min(5000, config.getInt("activity.capacity", 500)));

        tracked.clear();
        ConfigurationSection section = config.getConfigurationSection("activity.track");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            tracked.put(key.toLowerCase(Locale.ROOT), section.getBoolean(key, true));
        }
    }

    /**
     * Whether a type is being recorded. An unknown type defaults to on: a module reporting
     * something this build's config has never heard of should be visible, not silently dropped.
     */
    public boolean tracks(String type) {
        return enabled && tracked.getOrDefault(type, Boolean.TRUE);
    }

    // ------------------------------------------------------------------ writing

    /** Records one event. Safe from any thread. */
    public void record(String type, Player player, String detail) {
        record(type, player == null ? null : player.getName(), detail);
    }

    public void record(String type, String playerName, String detail) {
        if (!tracks(type)) return;
        entries.addFirst(new Entry(System.currentTimeMillis(), type, playerName, detail));
        trim();
        version.incrementAndGet();
    }

    private void trim() {
        while (entries.size() > capacity) entries.pollLast();
    }

    /** Bumped on every entry; the event stream uses it to skip pushing an unchanged feed. */
    public long version() {
        return version.get();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        version.incrementAndGet();
    }

    // ------------------------------------------------------------------ reading

    /** The most recent entries, newest first. */
    public List<Entry> recent(int limit) {
        List<Entry> result = new ArrayList<>(Math.min(limit, entries.size()));
        for (Entry entry : entries) {
            if (result.size() >= limit) break;
            result.add(entry);
        }
        return result;
    }

    /** Everything one player did, newest first. */
    public List<Entry> forPlayer(String playerName, int limit) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (result.size() >= limit) break;
            if (entry.player() != null && entry.player().equalsIgnoreCase(playerName)) result.add(entry);
        }
        return result;
    }

    /** The whole feed as a JSON array, for the panel. */
    public String json(int limit) {
        return Json.array(recent(limit), (writer, entry) -> writer.obj(json -> json
                .num("time", entry.time())
                .str("type", entry.type())
                .str("player", entry.player() == null ? "" : entry.player())
                .str("detail", entry.detail() == null ? "" : entry.detail())));
    }

    // ---------------------------------------------------------------- listeners

    /*
     * Join, quit and commands are handled here rather than in the modules that already listen for
     * them: they are server-wide facts, not any one feature's business, and a player who runs a
     * command should show up in the feed whether or not the chat module happens to be running.
     */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var address = player.getAddress();
        record(JOIN, player, address == null ? "" : address.getHostString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var data = plugin.getManagers().playerData().get(event.getPlayer());
        record(QUIT, event.getPlayer(),
                data == null ? "" : "sessione " + formatDuration(data.playtime()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // Only the command word, never the arguments: a feed that keeps every argument keeps
        // whatever somebody typed into /login or /register too.
        String typed = event.getMessage();
        int space = typed.indexOf(' ');
        record(COMMAND, event.getPlayer(), space < 0 ? typed : typed.substring(0, space));
    }

    private static String formatDuration(long millis) {
        long minutes = millis / 60_000L;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
