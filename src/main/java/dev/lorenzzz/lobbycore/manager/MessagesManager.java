package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Every player-facing string the plugin sends, read from messages.yml and resolved through
 * {@link C}. Keys missing from the admin's file fall back to the bundled defaults, so a message
 * added by an update never shows up blank.
 *
 * <p>Dove finiscono lo decide {@code delivery}: chat, action bar, o tutte e due. E una scelta
 * per tutto il plugin e non per messaggio, perche il senso e "come parla questo server", non
 * "come parla questa riga". Chi non e un giocatore — la console — legge sempre e solo la chat:
 * una action bar non ce l'ha.
 */
public final class MessagesManager implements Manager {

    /** Dove viene consegnato un messaggio. */
    public enum Delivery {
        CHAT, ACTIONBAR, BOTH;

        static Delivery of(String name) {
            try {
                return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                C.warning("delivery '" + name + "' sconosciuto in messages.yml — uso BOTH. "
                        + "Ammessi: chat, actionbar, both.");
                return BOTH;
            }
        }
    }

    private final LobbyCore plugin;
    private YamlConfiguration yaml;
    private String prefix = "";
    private Delivery delivery = Delivery.BOTH;

    public MessagesManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "messages"; }

    @Override
    public void enable() {
        reload();
    }

    @Override
    public void disable() {
        yaml = null;
    }

    @Override
    public void reload() {
        yaml = plugin.getManagers().config().load("messages.yml");
        prefix = yaml.getString("prefix", "");
        delivery = Delivery.of(yaml.getString("delivery", "both"));
    }

    public Delivery delivery() {
        return delivery;
    }

    public String prefix() {
        return prefix;
    }

    public boolean has(String path) {
        return !raw(path).isEmpty();
    }

    /** The configured line with {@code {prefix}} already substituted, or empty when unset. */
    public String raw(String path) {
        if (yaml == null) return "";
        String value = yaml.getString(path, "");
        return value.isEmpty() ? "" : value.replace("{prefix}", prefix);
    }

    public Component component(String path, Player player, Map<String, ?> placeholders) {
        return C.parse(raw(path), player, placeholders);
    }

    public void send(CommandSender target, String path) {
        send(target, path, null);
    }

    public void send(CommandSender target, String path, Map<String, ?> placeholders) {
        String message = raw(path);
        if (message.isEmpty()) return;

        Player player = target instanceof Player p ? p : null;
        Component rendered = C.parse(message, player, placeholders);

        // La console non ha una action bar: per lei la chat non e un'opzione ma l'unica strada,
        // anche quando il file dice actionbar.
        if (player == null || delivery != Delivery.ACTIONBAR) target.sendMessage(rendered);
        if (player != null && delivery != Delivery.CHAT) player.sendActionBar(rendered);
    }

    /** Solo action bar, qualunque cosa dica {@code delivery}: per chi la vuole per forza li. */
    public void actionBar(Player target, String path, Map<String, ?> placeholders) {
        String message = raw(path);
        if (message.isEmpty()) return;
        target.sendActionBar(C.parse(message, target, placeholders));
    }

    /**
     * Sends one message to everybody, rendering it once when it can be.
     *
     * <p>Looping over {@code send} redoes the whole pipeline per player — a YAML read, a
     * substitution pass and a deserialization — for a line that is usually identical for
     * everyone. The cost is per player and the message is not, which is the wrong way round.
     * So the shared work happens once, and only a line that really is personal (it still holds
     * a {@code %…%} PlaceholderAPI would fill in) falls back to the per-player path.
     */
    public void broadcast(String path, Map<String, ?> placeholders) {
        String message = C.replace(raw(path), placeholders);
        if (message.isEmpty()) return;

        if (!C.personal(message)) {
            Component shared = C.mm(message);
            for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(shared);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(C.parse(message, player, null));
        }
    }
}
