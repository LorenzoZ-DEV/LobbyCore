package dev.lorenzzz.lobbycore.module.modules.chat;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chat that reaches every lobby on the network. A fork of RedisChat, wired into the shared Redis
 * bus instead of opening its own connection.
 *
 * <p>The security-shaped part is {@link #sanitise}: a chat message is player input, and
 * MiniMessage is a markup language. Deserialising player text with the full tag set lets anyone
 * write a {@code <click:run_command>} into chat that fires as whoever clicks it, or a
 * {@code <hover:show_item>} carrying a crafted payload. So player text goes through a MiniMessage
 * instance that only knows colours and decorations, and the dangerous tags are stripped before it
 * even reaches that. The format string around it is written by the admin and is parsed with the
 * full pipeline, because that one is trusted.
 *
 * <p>Colour in messages is permission-gated, the way a server normally sells it.
 */
public final class ChatModule extends AbstractModule implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * Field separator for the Redis payload. A control character cannot appear in a player name, a rank
     * prefix or a sanitised message, so there is no escaping to get wrong — and unlike a space it
     * does not split a chat message that contains one.
     */
    private static final String FIELD_SEPARATOR = String.valueOf((char) 1);

    /** Tags that do more than colour text, and that player input has no business carrying. */
    private static final Pattern UNSAFE_TAGS = Pattern.compile(
            "(?i)</?(?:click|hover|insertion|font|selector|score|nbt|translatable|keybind)(?::[^>]*)?>");

    private static final Pattern LEGACY_CODES = Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");
    private static final Pattern HEX_CODES = Pattern.compile("[&§]#[0-9a-fA-F]{6}");

    /** Colours and decorations only: everything a player may legitimately want, nothing else. */
    private static final MiniMessage SAFE = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.decorations())
                    .resolver(StandardTags.gradient())
                    .resolver(StandardTags.rainbow())
                    .resolver(StandardTags.reset())
                    .build())
            .strict(false)
            .build();

    private static final String COLOR_PERMISSION = "lobbycore.chat.color";

    private String format;
    private String channel;
    private boolean crossServer;
    private boolean allowFormatting;
    private boolean tabSync;

    public ChatModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "chat"; }
    @Override public String configFile() { return "chat"; }

    @Override
    public String description() {
        return "Chat cross-lobby via Redis (fork di RedisChat)";
    }

    @Override
    public void onEnable() {
        var config = config();
        format = config.getString("chat.format",
                "&8({server}&8) {prefix}{player}&7: &f{message}");
        channel = config.getString("chat.redis_channel", "lobbycore:chat");
        crossServer = config.getBoolean("chat.cross_server", true);
        allowFormatting = config.getBoolean("chat.allow_formatting", true);
        tabSync = config.getBoolean("chat.tab_complete_sync", true);

        listen(this);

        if (crossServer && managers.redis().isEnabled()) {
            managers.redis().subscribe(channel, this::onRemoteMessage);
            C.info("Chat cross-lobby attiva sul canale &f" + channel + "&7.");
        } else if (crossServer) {
            C.info("Chat cross-lobby richiesta ma Redis non e attivo — chat solo locale.");
        }
    }

    @Override
    protected void shutdown() {
        if (crossServer && managers.redis().isEnabled()) {
            managers.redis().unsubscribe(channel);
        }
    }

    // ------------------------------------------------------------------- local

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = sanitise(PLAIN.serialize(event.message()), player);
        String prefix = prefixOf(player);
        String server = managers.redis().serverName();

        // We render the line ourselves, so the vanilla delivery has to stop here — otherwise
        // everybody sees the message twice.
        event.setCancelled(true);

        managers.activity().record(ActivityLog.CHAT, player, message);

        Component rendered = render(server, player.getName(), prefix, message);
        for (Player online : Bukkit.getOnlinePlayers()) online.sendMessage(rendered);
        Bukkit.getConsoleSender().sendMessage(rendered);

        if (crossServer && managers.redis().isEnabled()) {
            managers.redis().publish(channel, String.join(FIELD_SEPARATOR,
                    server, player.getName(), prefix, message));
        }
    }

    /** Runs on the Redis subscriber thread, so it touches nothing that needs the main one. */
    private void onRemoteMessage(String payload) {
        String[] parts = payload.split(FIELD_SEPARATOR, 4);
        if (parts.length != 4) return;
        // Our own message coming back around: publish reaches every subscriber including us.
        if (parts[0].equals(managers.redis().serverName())) return;

        Component rendered = render(parts[0], parts[1], parts[2], parts[3]);
        for (Player online : Bukkit.getOnlinePlayers()) online.sendMessage(rendered);
    }

    /**
     * Builds the chat line: the admin's format parsed fully, the player's message parsed safely,
     * and the two joined as components so nothing inside the message can escape into the format.
     */
    private Component render(String server, String playerName, String prefix, String message) {
        String base = format
                .replace("{server}", server)
                .replace("{player}", playerName)
                .replace("{prefix}", prefix);

        int index = base.indexOf("{message}");
        if (index < 0) return C.mm(base);

        Component before = C.mm(base.substring(0, index));
        Component body = SAFE.deserialize(C.normalise(message));
        String tail = base.substring(index + "{message}".length());

        return tail.isEmpty() ? before.append(body) : before.append(body).append(C.mm(tail));
    }

    /**
     * Strips whatever this player is not allowed to send. Order matters: the dangerous tags go
     * first and unconditionally, so a player who does have the colour permission still cannot
     * send a click event.
     */
    private String sanitise(String text, Player player) {
        String result = UNSAFE_TAGS.matcher(text).replaceAll("");
        if (allowFormatting && player.hasPermission(COLOR_PERMISSION)) return result;

        result = MiniMessage.miniMessage().stripTags(result);
        result = HEX_CODES.matcher(result).replaceAll("");
        return LEGACY_CODES.matcher(result).replaceAll("");
    }

    private String prefixOf(Player player) {
        return managers.hooks().hasLuckPerms() ? managers.hooks().luckPerms().prefix(player) : "";
    }

    // ------------------------------------------------------------- tab-complete

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (tabSync) managers.redis().addToRoster(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (tabSync) managers.redis().removeFromRoster(event.getPlayer().getName());
    }

    /**
     * Offers names from the other lobbies when completing a command.
     *
     * <p>Hooked on the async completion event rather than on a command's own completer: this has
     * to apply to every command the network runs — {@code /msg}, {@code /friend}, whatever the
     * proxy adds — and there is no way to reach those from here.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (!tabSync || !managers.redis().isEnabled()) return;

        String buffer = event.getBuffer();
        if (buffer.isEmpty()) return;

        String[] parts = buffer.split(" ", -1);
        if (buffer.startsWith("/") && parts.length < 2) return;

        String token = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        if (token.isEmpty()) return;

        Set<String> remote = managers.redis().networkPlayers();
        for (String name : remote) {
            // Skip anyone who is on this server too: Bukkit already completes them, and the same
            // name twice is what makes a completion list look broken.
            if (Bukkit.getPlayerExact(name) != null) continue;
            if (name.toLowerCase(Locale.ROOT).startsWith(token)) {
                event.completions().add(AsyncTabCompleteEvent.Completion.completion(name));
            }
        }
    }
}
