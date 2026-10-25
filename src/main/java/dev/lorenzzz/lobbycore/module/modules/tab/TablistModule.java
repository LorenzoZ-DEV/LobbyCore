package dev.lorenzzz.lobbycore.module.modules.tab;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.hook.LuckPermsHook;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.Animations;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Header, footer and player-list formatting, in the shape TAB's config uses — the same
 * {@code header-footer.designs.default} block and the same {@code %animation:name%} frames, so an
 * existing TAB config transfers over.
 *
 * <p>Sorting works through scoreboard teams, because that is what a client orders the tab list
 * by. They live on the <em>main</em> scoreboard and are registered once per player: the sidebar
 * is drawn by FastBoard, which sends packets rather than handing anyone their own Scoreboard, so
 * nothing ever takes a player off the board these teams are on. An earlier build had to replicate
 * every team onto every viewer's private scoreboard and re-apply them whenever the sidebar was
 * rebuilt; none of that is needed now.
 *
 * <p>The one case this cannot cover is another plugin moving players onto a scoreboard of its own.
 * Then its teams decide the order, and {@code sorting.enabled: false} is the honest setting.
 *
 * <p>Header and footer do run on the timer, because they animate. They are rendered once per
 * player, and a design with no per-player placeholder is rendered once for everybody.
 */
public final class TablistModule extends AbstractModule implements Listener {

    /** Team names are built from a zero-padded sort key so the client orders them for us. */
    private static final int MAX_NAME_PART = 12;

    private final Animations animations = new Animations();

    private boolean headerFooterEnabled;
    /**
     * Header e footer gia uniti con gli a capo, e se vanno renderizzati per singolo giocatore.
     * Non cambiano fino al reload: calcolarli una volta qui evita due join e due regex per tick.
     */
    private String joinedHeader = "";
    private String joinedFooter = "";
    private boolean personalHeaderFooter;

    private boolean sortingEnabled;
    private boolean formatNames;
    private String nameFormat;

    private boolean nametagsEnabled;
    private String nametagPrefix;
    private String nametagSuffix;

    public TablistModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "tablist"; }
    @Override public String configFile() { return "tablist"; }

    @Override
    public String description() {
        return "Header, footer, ordinamento della tablist e nametag sopra la testa";
    }

    @Override
    public void onEnable() {
        var config = config();

        headerFooterEnabled = config.getBoolean("header-footer.enabled", true);
        String design = config.getString("header-footer.design", "default");
        joinedHeader = String.join("\n",
                config.getStringList("header-footer.designs." + design + ".header"));
        joinedFooter = String.join("\n",
                config.getStringList("header-footer.designs." + design + ".footer"));
        personalHeaderFooter = personal(joinedHeader) || personal(joinedFooter);

        sortingEnabled = config.getBoolean("sorting.enabled", true);
        nametagsEnabled = config.getBoolean("nametags.enabled", true);
        nametagPrefix = config.getString("nametags.prefix", "%luckperms_prefix%");
        nametagSuffix = config.getString("nametags.suffix", "%luckperms_suffix%");
        formatNames = config.getBoolean("name-format.enabled", true);
        nameFormat = config.getString("name-format.format", "%luckperms_prefix%%player%");

        animations.load(managers.config().module("tablist_animations"));

        long updateTicks = Math.max(1L, config.getLong("header-footer.update_interval", 5L));

        listen(this);
        if (headerFooterEnabled) repeat(this::tick, updateTicks, updateTicks);

        refreshAll();
    }

    @Override
    protected void shutdown() {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            viewer.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            viewer.playerListName(null);
        }
        clearTeams();
    }

    // ------------------------------------------------------------ header/footer

    private void tick() {
        // A design without a single per-player placeholder is the common case: render it once
        // and hand the same pair of components to everybody.
        Component sharedHeader = personalHeaderFooter ? null : C.mm(animations.apply(joinedHeader));
        Component sharedFooter = personalHeaderFooter ? null : C.mm(animations.apply(joinedFooter));

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!inLobbyWorld(player.getWorld())) continue;

            if (!personalHeaderFooter) {
                player.sendPlayerListHeaderAndFooter(sharedHeader, sharedFooter);
                continue;
            }
            Map<String, String> replacements = Map.of("%player%", player.getName());
            // Stessa protezione della scoreboard: i tag animazione non devono finire in
            // PlaceholderAPI, dove un'altra expansion col prefisso animation: li mangerebbe.
            Animations.Guarded guardedHeader = Animations.guard(joinedHeader);
            Animations.Guarded guardedFooter = Animations.guard(joinedFooter);
            player.sendPlayerListHeaderAndFooter(
                    C.mm(animations.apply(guardedHeader.restore(
                            C.replace(guardedHeader.text(), player, replacements)))),
                    C.mm(animations.apply(guardedFooter.restore(
                            C.replace(guardedFooter.text(), player, replacements)))));
        }
    }

    /**
     * Se header o footer vanno renderizzati per singolo giocatore.
     *
     * <p>I tag {@code %animation:...%} si tolgono prima del controllo: danno lo stesso frame a
     * tutti, quindi un footer fatto solo di animazioni deve restare sul percorso condiviso
     * invece di costare una passata PlaceholderAPI a testa. {@code %player%} invece conta
     * sempre, perche lo sostituiamo noi anche senza PlaceholderAPI installato.
     */
    private static boolean personal(String text) {
        String withoutAnimations = Animations.without(text);
        return withoutAnimations.contains("%player%") || C.personal(withoutAnimations);
    }

    // ------------------------------------------------------------------ names

    private void applyName(Player player) {
        if (!formatNames) return;
        LuckPermsHook luckPerms = managers.hooks().luckPerms();
        String prefix = luckPerms == null ? "" : luckPerms.prefix(player);
        String suffix = luckPerms == null ? "" : luckPerms.suffix(player);

        // I valori LuckPerms sono esposti con entrambi i nomi: %prefix%/%suffix% e quelli
        // dell'expansion PAPI, che sono quelli usati dal formato di default. Senza i secondi
        // il nome in tablist mostra "%luckperms_prefix%" scritto quando PlaceholderAPI (o la
        // sua expansion LuckPerms) non c'e — e i valori qui ce li abbiamo gia in mano.
        String rendered = C.replace(nameFormat, player, Map.of(
                "%player%", player.getName(),
                "%prefix%", prefix,
                "%suffix%", suffix,
                "%luckperms_prefix%", prefix,
                "%luckperms_suffix%", suffix));
        player.playerListName(C.mm(rendered));
    }

    // ---------------------------------------------------------------- sorting

    /**
     * The team a player belongs to. Higher LuckPerms weight sorts first, so the key is the
     * weight inverted; the name is appended to keep the team unique per player.
     */
    private String teamName(Player player) {
        LuckPermsHook luckPerms = managers.hooks().luckPerms();
        int weight = luckPerms == null ? 0 : luckPerms.weight(player);
        // Con l'ordinamento spento i team servono solo alle nametag: stesso numero per tutti,
        // cosi il client non ha niente da ordinare e l'ordine resta quello del server.
        int order = sortingEnabled ? Math.max(0, Math.min(999, 999 - weight)) : 999;

        String name = player.getName().toLowerCase(Locale.ROOT);
        if (name.length() > MAX_NAME_PART) name = name.substring(0, MAX_NAME_PART);
        return String.format("%03d", order) + name;
    }

    /**
     * Il nome sopra la testa: prefix e suffix del team a cui il giocatore appartiene.
     *
     * <p>Passa dagli stessi valori LuckPerms del nome in tablist, quindi grado e colore sono
     * gli stessi nei due posti senza doverli scrivere due volte. Il nome in mezzo resta quello
     * vero — sopra la testa Minecraft non lo lascia cambiare, si puo solo incorniciare.
     */
    private void applyNametag(Player target, Team team) {
        if (!nametagsEnabled) return;
        LuckPermsHook luckPerms = managers.hooks().luckPerms();
        String prefix = luckPerms == null ? "" : luckPerms.prefix(target);
        String suffix = luckPerms == null ? "" : luckPerms.suffix(target);

        Map<String, String> values = Map.of(
                "%player%", target.getName(),
                "%prefix%", prefix,
                "%suffix%", suffix,
                "%luckperms_prefix%", prefix,
                "%luckperms_suffix%", suffix);

        team.prefix(C.mm(C.replace(nametagPrefix, target, values)));
        team.suffix(C.mm(C.replace(nametagSuffix, target, values)));
    }

    /** The main scoreboard — the one every player is on unless another plugin moved them. */
    private Scoreboard board() {
        return plugin.getServer().getScoreboardManager().getMainScoreboard();
    }

    /**
     * I team servono sia all'ordinamento sia alle nametag: sono lo stesso oggetto, e il nome
     * sopra la testa e il prefix del team a cui il giocatore appartiene. Basta che una delle
     * due cose sia accesa perche valga la pena crearli.
     */
    private boolean teamsNeeded() {
        return sortingEnabled || nametagsEnabled;
    }

    /** Puts one player into the team their rank earns them. */
    private void assign(Player target) {
        if (!teamsNeeded()) return;
        String name = teamName(target);
        Scoreboard scoreboard = board();

        Team team = scoreboard.getTeam(name);
        if (team == null) team = scoreboard.registerNewTeam(name);

        // Prima dell'uscita anticipata: a un cambio di grado il giocatore puo restare nello
        // stesso team (stesso peso, stesso nome) e avere comunque un prefix nuovo da scrivere.
        applyNametag(target, team);

        if (team.hasEntry(target.getName())) return;

        // addEntry moves the player off whatever team they were on, which is exactly what a rank
        // change needs; quello che resta vuoto va deregistrato subito (vedi sweep).
        Team previous = scoreboard.getEntryTeam(target.getName());
        team.addEntry(target.getName());
        sweep(previous);
    }

    private void unassign(Player target) {
        if (!teamsNeeded()) return;
        Team team = board().getEntryTeam(target.getName());
        if (team == null) return;
        team.removeEntry(target.getName());
        sweep(team);
    }

    /**
     * Deregistra un nostro team rimasto senza giocatori.
     *
     * <p>I team sono uno per giocatore e vivono sulla scoreboard principale, che dura quanto
     * il server: lasciarli registrati vuoti significa accumularne uno per ogni unico passato
     * dalla lobby, e la main scoreboard viene sincronizzata a ogni client che entra. Su una
     * lobby vera sono migliaia di team inutili nel giro di qualche giorno.
     */
    private static void sweep(Team team) {
        if (team == null || !isOurTeam(team.getName())) return;
        try {
            if (team.getEntries().isEmpty()) team.unregister();
        } catch (IllegalStateException ignored) {
            // Gia deregistrato da qualcun altro.
        }
    }

    /** Unregisters only the teams this module made, leaving anyone else's alone. */
    private void clearTeams() {
        for (Team team : List.copyOf(board().getTeams())) {
            if (!isOurTeam(team.getName())) continue;
            try {
                team.unregister();
            } catch (IllegalStateException ignored) {
                // Already gone.
            }
        }
    }

    /** Our team names are three digits followed by a player name; nothing else looks like that. */
    private static boolean isOurTeam(String name) {
        return name.length() > 3
                && Character.isDigit(name.charAt(0))
                && Character.isDigit(name.charAt(1))
                && Character.isDigit(name.charAt(2));
    }

    /** Re-applies names and sorting for everybody. Used at start-up and after a rank change. */
    public void refreshAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            applyName(player);
            assign(player);
        }
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // A couple of ticks late so LuckPerms has the player's data loaded; asking for a weight
        // before that gets the default group and sorts a staffer in with everyone else.
        later(() -> {
            if (!player.isOnline()) return;
            applyName(player);
            assign(player);
        }, 4L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        unassign(event.getPlayer());
    }
}
