package dev.lorenzzz.lobbycore.module.modules.chat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The moderation layer in front of chat: a word filter, a command blocklist, an anti-repeat and
 * a per-player cooldown, plus a global chat lock for when staff need the room quiet.
 *
 * <p>It runs at {@link EventPriority#LOWEST} so a blocked message never reaches the chat module
 * that would broadcast it — and never reaches Redis either, which matters because a message
 * published to the bus is on every lobby before any local filter could take it back.
 *
 * <p>The word filter matches on a normalised copy of the message: lower-cased, with the usual
 * letter/digit substitutions folded back and repeated characters collapsed. Matching the raw
 * text catches only the players who were not trying.
 */
public final class ChatFilterModule extends AbstractModule implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern REPEATS = Pattern.compile("(.)\\1{2,}");
    private static final Pattern NON_LETTERS = Pattern.compile("[^a-z0-9]");

    private static final String BYPASS = "lobbycore.chat.bypass";

    private boolean filterEnabled;
    private boolean blockMessage;
    private final List<Pattern> blocked = new ArrayList<>();

    private boolean commandBlockEnabled;
    private final Set<String> blockedCommands = new HashSet<>();

    private boolean cooldownEnabled;
    private long cooldownMillis;

    private boolean antiRepeatEnabled;

    private volatile boolean locked;

    private final Cache<UUID, Long> lastMessage = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Cache<UUID, String> lastText = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).build();

    public ChatFilterModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "chat-filter"; }
    @Override public String configFile() { return "chat_filter"; }

    @Override
    public String description() {
        return "Filtro parole, blocco comandi, antispam e chat lock";
    }

    @Override
    public void onEnable() {
        var config = config();
        String root = "chat_filter.";

        filterEnabled = config.getBoolean(root + "word_filter.enabled", true);
        blockMessage = config.getBoolean(root + "word_filter.block_message", true);
        blocked.clear();
        for (String word : config.getStringList(root + "word_filter.words")) {
            if (word == null || word.isBlank()) continue;
            // Word-boundary matching on the normalised text: a substring match turns every
            // innocent word containing a short blocked one into a false positive.
            blocked.add(Pattern.compile("\\b" + Pattern.quote(normalise(word)) + "\\b"));
        }

        commandBlockEnabled = config.getBoolean(root + "command_block.enabled", true);
        blockedCommands.clear();
        for (String command : config.getStringList(root + "command_block.commands")) {
            blockedCommands.add(command.toLowerCase(Locale.ROOT).replace("/", "").trim());
        }

        cooldownEnabled = config.getBoolean(root + "cooldown.enabled", true);
        cooldownMillis = config.getLong(root + "cooldown.seconds", 2L) * 1000L;
        antiRepeatEnabled = config.getBoolean(root + "anti_repeat.enabled", true);

        locked = false;
        listen(this);
    }

    @Override
    protected void shutdown() {
        blocked.clear();
        blockedCommands.clear();
        lastMessage.invalidateAll();
        lastText.invalidateAll();
        locked = false;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean value) {
        locked = value;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;

        if (locked) {
            event.setCancelled(true);
            managers.messages().send(player, "chat-locked");
            return;
        }

        String raw = PLAIN.serialize(event.message());
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (cooldownEnabled) {
            Long last = lastMessage.getIfPresent(uuid);
            if (last != null && now - last < cooldownMillis) {
                event.setCancelled(true);
                long remaining = (cooldownMillis - (now - last) + 999) / 1000;
                managers.messages().send(player, "chat-cooldown", Map.of("%seconds%", remaining));
                return;
            }
        }

        if (antiRepeatEnabled) {
            String previous = lastText.getIfPresent(uuid);
            if (previous != null && previous.equalsIgnoreCase(raw.trim())) {
                event.setCancelled(true);
                managers.messages().send(player, "chat-repeat");
                return;
            }
        }

        if (filterEnabled && matchesFilter(raw)) {
            managers.activity().record(ActivityLog.BLOCKED, player, "parola filtrata");
            managers.messages().send(player, "chat-blocked-word");
            if (blockMessage) {
                event.setCancelled(true);
                return;
            }
        }

        lastMessage.put(uuid, now);
        lastText.put(uuid, raw.trim());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!commandBlockEnabled) return;
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;

        // Take the first word, drop the slash, and drop any plugin namespace: /bukkit:plugins
        // and /plugins are the same command and a blocklist that misses one is not a blocklist.
        String typed = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = typed.indexOf(':');
        String bare = colon >= 0 ? typed.substring(colon + 1) : typed;

        if (blockedCommands.contains(typed) || blockedCommands.contains(bare)) {
            event.setCancelled(true);
            managers.activity().record(ActivityLog.BLOCKED, player, "/" + bare);
            managers.messages().send(player, "command-blocked");
        }
    }

    private boolean matchesFilter(String message) {
        if (blocked.isEmpty()) return false;
        String normalised = normalise(message);
        for (Pattern pattern : blocked) {
            if (pattern.matcher(normalised).find()) return true;
        }
        return false;
    }

    /**
     * Folds the usual evasions back: {@code @} and {@code 4} to {@code a}, {@code 3} to
     * {@code e}, spacing and punctuation out, and runs of the same letter down to one. The
     * result is not readable text, which is fine — nothing but the filter ever sees it.
     */
    private static String normalise(String input) {
        String lower = input.toLowerCase(Locale.ROOT)
                .replace('@', 'a').replace('4', 'a')
                .replace('3', 'e')
                .replace('1', 'i').replace('!', 'i').replace('|', 'i')
                .replace('0', 'o')
                .replace('$', 's').replace('5', 's')
                .replace('7', 't');
        String collapsed = REPEATS.matcher(lower).replaceAll("$1");
        return NON_LETTERS.matcher(collapsed).replaceAll(" ").trim();
    }
}
