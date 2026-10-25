package dev.lorenzzz.lobbycore.module.modules.scoreboard;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.hook.LuckPermsHook;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.module.modules.pvp.HubPvpModule;
import dev.lorenzzz.lobbycore.util.Animations;
import fr.mrmicky.fastboard.adventure.FastBoard;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The animated sidebar, drawn with MrMicky's FastBoard.
 *
 * <p>FastBoard sends the scoreboard packets itself instead of going through a Bukkit
 * {@code Scoreboard}, and that is the whole reason it is here. The usual approach — a fresh
 * Scoreboard per player, one team per line, the text in the team prefix — has two costs this
 * lobby actually paid: the client flickers on every redraw, and handing a player a new Scoreboard
 * throws away the teams the {@code tablist} module uses to order the player list. With packets,
 * everyone stays on the main scoreboard and the two modules stop fighting.
 *
 * <p>The other decision worth keeping is where the caching sits. Placeholders are expensive —
 * every {@code %bungee_total%} is a call into another plugin, and at one line per player per tick
 * a full lobby is thousands of them a second — but animation frames are meant to change fast. So
 * resolved placeholder text is cached per player per line for a short TTL, and the animation
 * substitution runs <em>after</em> the cache on every draw. That lets an admin set a 2-tick
 * update interval for smooth animations without paying for a thousand placeholder lookups a
 * second to get it.
 */
public final class ScoreboardModule extends AbstractModule implements Listener {

    /** FastBoard addresses lines by colour code, and there are only so many of those. */
    private static final int MAX_LINES = 22;

    private final Map<UUID, FastBoard> boards = new ConcurrentHashMap<>();
    private final Animations animations = new Animations();

    private String title;
    private List<String> lines = List.of();
    private boolean hideWhenPvp;

    /** Key is {@code uuid:lineIndex}; value is the line with placeholders already resolved. */
    private Cache<String, String> resolved;

    /** False when FastBoard could not wire itself to this server; the module then does nothing. */
    private boolean usable;

    public ScoreboardModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "scoreboard"; }
    @Override public String configFile() { return "scoreboard"; }

    @Override
    public String description() {
        return "Scoreboard animata (FastBoard)";
    }

    @Override
    public void onEnable() {
        usable = probeFastBoard();
        if (!usable) return;

        var config = config();
        title = config.getString("scoreboard.title", "&dLOBBY");
        hideWhenPvp = config.getBoolean("scoreboard.hide_during_pvp", true);

        lines = config.getStringList("scoreboard.lines");
        if (lines.size() > MAX_LINES) {
            C.warning("La scoreboard ha " + lines.size() + " righe, il massimo e " + MAX_LINES
                    + " — le altre vengono ignorate.");
            lines = List.copyOf(lines.subList(0, MAX_LINES));
        }

        long updateTicks = Math.max(1L, config.getLong("scoreboard.update_interval", 3L));
        long ttlMillis = Math.max(1L, config.getLong("scoreboard.placeholder_cache_ttl_ms", 1500L));

        resolved = Caffeine.newBuilder()
                .expireAfterWrite(ttlMillis, TimeUnit.MILLISECONDS)
                .maximumSize(4096)
                .build();

        animations.load(managers.config().module("tablist_animations"));

        listen(this);
        repeat(this::tick, updateTicks, updateTicks);

        // Una riga sola per poter verificare cosa gira davvero: l'intervallo nel file del
        // server vince sempre sul default del jar (il merge non sovrascrive mai i valori).
        C.info("Scoreboard attiva: redraw ogni &f" + updateTicks + "&7 tick, &f" + lines.size()
                + "&7 righe, cache placeholder &f" + ttlMillis + "&7ms.");

        for (Player player : plugin.getServer().getOnlinePlayers()) show(player);
    }

    /**
     * Loads FastBoard now rather than at the first join.
     *
     * <p>FastBoard resolves the server's packet classes in a static initialiser, so a server it
     * cannot read makes {@code new FastBoard(player)} throw {@link ExceptionInInitializerError} —
     * and without this, that would first happen inside a join handler, once per player, long after
     * anybody was watching the console. A class literal does not trigger initialisation, hence the
     * explicit {@code Class.forName}.
     */
    private boolean probeFastBoard() {
        try {
            Class.forName(FastBoard.class.getName(), true, FastBoard.class.getClassLoader());
            return true;
        } catch (Throwable failure) {
            C.warning("FastBoard non riesce ad agganciarsi a questo server ("
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + ") — la scoreboard resta spenta.");
            return false;
        }
    }

    @Override
    protected void shutdown() {
        // delete() sends the packet that removes the objective. Skipping it would leave the
        // sidebar frozen on the client until it reconnects.
        for (FastBoard board : boards.values()) {
            if (!board.isDeleted()) board.delete();
        }
        boards.clear();
        if (resolved != null) resolved.invalidateAll();
    }

    private void show(Player player) {
        if (!usable || !inLobbyWorld(player.getWorld())) {
            hide(player);
            return;
        }
        board(player);
    }

    /**
     * Crea o restituisce la board del giocatore. FastBoard risolve i pacchetti NMS a runtime:
     * su una versione non supportata il costruttore tira — e senza questo try-catch
     * l'eccezione uscirebbe dal task ogni tick con spam in console e nessuna scoreboard.
     * Al primo fallimento il modulo si spegne da solo con un solo avviso.
     */
    private FastBoard board(Player player) {
        FastBoard existing = boards.get(player.getUniqueId());
        if (existing != null) {
            if (!existing.isDeleted()) return existing;
            boards.remove(player.getUniqueId());
        }
        try {
            FastBoard created = new FastBoard(player);
            boards.put(player.getUniqueId(), created);
            return created;
        } catch (Throwable failure) {
            boards.remove(player.getUniqueId());
            if (usable) {
                usable = false;
                C.warning("FastBoard non riesce a creare la scoreboard su questo server ("
                        + failure.getClass().getSimpleName()
                        + (failure.getMessage() != null ? ": " + failure.getMessage() : "")
                        + ") — la scoreboard resta spenta.");
            }
            return null;
        }
    }

    private void hide(Player player) {
        FastBoard board = boards.remove(player.getUniqueId());
        if (board != null && !board.isDeleted()) board.delete();
    }

    private void tick() {
        if (!usable || lines.isEmpty()) return;

        HubPvpModule pvp = managers.modules().module("hub-pvp", HubPvpModule.class);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!inLobbyWorld(player.getWorld())) {
                hide(player);
                continue;
            }
            // A sidebar over a fight is in the way, and the PvP module already owns the screen.
            if (hideWhenPvp && pvp != null && pvp.isActive(player)) {
                hide(player);
                continue;
            }

            FastBoard board = board(player);
            if (board == null || board.isDeleted()) {
                // Il giocatore si e riconnesso con lo stesso uuid, o la board e stata
                // invalidata: la si ricrea al prossimo tick.
                boards.remove(player.getUniqueId());
                continue;
            }

            board.updateTitle(C.mm(animations.apply(resolve(player, -1, title))));

            List<Component> rendered = new ArrayList<>(lines.size());
            for (int index = 0; index < lines.size(); index++) {
                rendered.add(C.mm(animations.apply(resolve(player, index, lines.get(index)))));
            }
            board.updateLines(rendered);
        }
    }

    /** Placeholder substitution for one line, memoised for the configured TTL. */
    private String resolve(Player player, int index, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // Non si passa da C.personal: quello richiede PlaceholderAPI, che qui e softdepend.
        // %player% e %luckperms_prefix/suffix% li risolviamo noi qui sotto e devono uscire
        // giusti anche su un server senza PAPI — altrimenti la sidebar mostra i placeholder
        // scritti. I tag animazione non contano: cambiano frame ad ogni disegno e restano
        // fuori dalla cache per costruzione.
        if (Animations.without(raw).indexOf('%') < 0) return raw;

        String key = player.getUniqueId() + ":" + index;
        return resolved.get(key, ignored -> {
            // %luckperms_prefix/suffix% risolti in casa: senza l'expansion PAPI di LuckPerms
            // resterebbero scritti letterali in scoreboard. prefix()/suffix() non sono mai null.
            String out = raw.replace("%player%", player.getName());
            LuckPermsHook luckPerms = managers.hooks().luckPerms();
            if (luckPerms != null) {
                out = out.replace("%luckperms_prefix%", luckPerms.prefix(player))
                        .replace("%luckperms_suffix%", luckPerms.suffix(player));
            }
            // I tag animazione passano PlaceholderAPI nascosti: vedi Animations#guard.
            Animations.Guarded guarded = Animations.guard(out);
            return guarded.restore(C.replace(guarded.text(), player, Map.of()));
        });
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // A tick late, so anything else that writes to the player on join has finished first.
        later(() -> {
            if (event.getPlayer().isOnline()) show(event.getPlayer());
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        FastBoard board = boards.remove(event.getPlayer().getUniqueId());
        if (board != null && !board.isDeleted()) board.delete();

        // The cache is keyed by uuid, so without this a busy lobby slowly fills it with lines
        // belonging to players who left.
        for (int index = -1; index < lines.size(); index++) {
            resolved.invalidate(event.getPlayer().getUniqueId() + ":" + index);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        show(event.getPlayer());
    }
}
