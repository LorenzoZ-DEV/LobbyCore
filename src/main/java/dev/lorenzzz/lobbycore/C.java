package dev.lorenzzz.lobbycore;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.lobbycore.util.Heads;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text pipeline for the whole plugin. Everything a lobby renders — scoreboard lines, tab header,
 * menu titles, item names, chat — goes through here.
 *
 * <p>MiniMessage is the target format, not an alternative to the legacy one. The configs this
 * plugin inherits mix the two freely ({@code '<shadow:#000000:1>&#8825DD&lV'} is a real
 * scoreboard line), so {@link #mm(String)} normalises {@code &#RRGGBB} and {@code &x} into tags
 * first and hands a single string to MiniMessage. Parsing them separately would mean one of the
 * two syntaxes silently winning.
 *
 * <p>The normalisation is cached: a scoreboard redraws every second per player off the same
 * handful of config lines, and the regex plus twenty-two {@code replace} passes per line per
 * player per tick is real work for a result that never changes.
 */
public final class C {

    private C() {}

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Pattern HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    /**
     * Il formato Bungee {@code &x&2&9&c&7&f&8}: va tradotto PRIMA di tutto, altrimenti il
     * passo legacy qui sotto mangerebbe {@code &2}, {@code &9}, ... come colori vanilla
     * e resterebbe {@code &x} letterale (il famoso "âx" nei nomi degli item).
     */
    private static final Pattern BUNGEE_HEX =
            Pattern.compile("[&§]x((?:[&§][0-9a-fA-F]){6})", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");
    private static final Pattern STRIP_SECTION = Pattern.compile("(?i)§[0-9A-FK-ORX]");

    /**
     * Legacy colour codes reset formatting in vanilla, MiniMessage tags do not. Without this
     * prefix a {@code &l} earlier in the line keeps bolding everything after the next {@code &7},
     * which is not what the config author wrote.
     */
    private static final String DECO_RESET =
            "<!bold><!italic><!obfuscated><!strikethrough><!underlined>";

    private static final Map<Character, String> COLOR_TAGS = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"), Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"), Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>")
    );

    private static final Map<Character, String> FORMAT_TAGS = Map.of(
            'k', "<obfuscated>", 'l', "<bold>", 'm', "<strikethrough>",
            'n', "<underlined>", 'o', "<italic>"
    );

    /** Input string -> MiniMessage-normalised string. Keyed on the raw input, so a reloaded
     *  config simply lands on new keys and the stale ones age out. */
    private static final Cache<String, String> NORMALISED = Caffeine.newBuilder()
            .maximumSize(2048)
            .build();

    /** Fully parsed components for text that holds no placeholder — tab footers, menu fillers. */
    private static final Cache<String, Component> COMPONENTS = Caffeine.newBuilder()
            .maximumSize(1024)
            .build();

    private static volatile boolean papi;

    // ----------------------------------------------------------------- parsing

    /** Normalises {@code &} codes and {@code &#RRGGBB} into MiniMessage tags. Cached. */
    public static String normalise(String text) {
        if (text == null || text.isEmpty()) return "";
        return NORMALISED.get(text, C::convert);
    }

    private static String convert(String text) {
        StringBuilder pre = new StringBuilder();
        Matcher bungee = BUNGEE_HEX.matcher(text);
        while (bungee.find()) {
            String digits = bungee.group(1).replace("&", "").replace("§", "");
            bungee.appendReplacement(pre,
                    Matcher.quoteReplacement(DECO_RESET + "<color:#" + digits + ">"));
        }
        bungee.appendTail(pre);

        StringBuilder out = new StringBuilder();
        Matcher hex = HEX.matcher(pre.toString());
        while (hex.find()) {
            hex.appendReplacement(out, Matcher.quoteReplacement(DECO_RESET + "<color:#" + hex.group(1) + ">"));
        }
        hex.appendTail(out);

        String withHex = out.toString();
        StringBuilder result = new StringBuilder(withHex.length());

        Matcher legacy = LEGACY.matcher(withHex);
        int last = 0;
        while (legacy.find()) {
            result.append(withHex, last, legacy.start());
            char code = Character.toLowerCase(legacy.group(1).charAt(0));
            if (code == 'r') {
                result.append("<reset>");
            } else {
                String color = COLOR_TAGS.get(code);
                if (color != null) {
                    result.append(DECO_RESET).append(color);
                } else {
                    result.append(FORMAT_TAGS.getOrDefault(code, ""));
                }
            }
            last = legacy.end();
        }
        result.append(withHex, last, withHex.length());
        return result.toString();
    }

    /** The main entry point: any config string -> a renderable component. */
    public static Component mm(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return MINI.deserialize(normalise(text), Heads.resolver());
    }

    /**
     * {@link #mm} with tag resolvers, so a value is handed to MiniMessage as a resolved tag
     * instead of being spliced into the text. Splicing first means whatever the value contains
     * is parsed as markup — which is how a player name becomes a click event.
     */
    public static Component mm(String text, TagResolver resolver) {
        if (text == null || text.isEmpty()) return Component.empty();
        if (resolver == null) return mm(text);
        return MINI.deserialize(normalise(text), TagResolver.resolver(Heads.resolver(), resolver));
    }

    /** {@link #mm} for text known to be static — the component itself is cached, not just the
     *  normalisation. Never use for anything holding a placeholder. */
    public static Component cached(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return COMPONENTS.get(text, C::mm);
    }

    /** {@link #mm} with the italic default items carry in vanilla turned off. */
    public static Component item(String text) {
        return mm(text).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> items(List<String> lines) {
        return lines.stream().map(C::item).toList();
    }

    /** Renders to plain text — for scoreboard team prefixes and log lines. */
    public static String plain(String text) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(mm(text));
    }

    public static String stripSection(String text) {
        return text == null ? "" : STRIP_SECTION.matcher(text).replaceAll("");
    }

    // ------------------------------------------------------------ placeholders

    /** Toggled once by the HookManager at boot. */
    public static void papi(boolean enabled) {
        papi = enabled;
    }

    public static boolean papiEnabled() {
        return papi;
    }

    /** {@code %key%} -> value. Pure string work: no colours, no PlaceholderAPI. */
    public static String replace(String text, Map<String, ?> placeholders) {
        if (text == null) return "";
        if (placeholders == null || placeholders.isEmpty()) return text;
        String out = text;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            out = out.replace(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return out;
    }

    /** {@code %key%} substitution followed by PlaceholderAPI for the given player. */
    public static String replace(String text, Player player, Map<String, ?> placeholders) {
        String out = replace(text, placeholders);
        if (papi && player != null) {
            out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, out);
        }
        return out;
    }

    /** Substitute (with PlaceholderAPI) then parse — the usual one-liner for module output. */
    public static Component parse(String text, Player player, Map<String, ?> placeholders) {
        return mm(replace(text, player, placeholders));
    }

    /**
     * Whether the text still needs a player to be resolved. A leftover {@code %} is the only
     * signal PlaceholderAPI gives us, so this errs towards "yes": a false positive costs one
     * broadcast its shared component, a false negative would send everyone the first player's
     * numbers.
     */
    public static boolean personal(String text) {
        return text != null && !text.isEmpty() && papi && text.indexOf('%') >= 0;
    }

    // ----------------------------------------------------------------- output

    public static void send(CommandSender target, String text) {
        if (text == null || text.isEmpty()) return;
        target.sendMessage(mm(text));
    }

    // ---------------------------------------------------------------- logging

    public static void info(String message) {
        console("&d[LobbyCore] &7" + message);
    }

    public static void warning(String message) {
        Bukkit.getLogger().warning("[LobbyCore] " + stripSection(message));
    }

    public static void error(String message) {
        Bukkit.getLogger().severe("[LobbyCore] " + stripSection(message));
    }

    public static void line() {
        console("&8&m                                                  ");
    }

    private static void console(String message) {
        Bukkit.getConsoleSender().sendMessage(mm(message));
    }

    // ----------------------------------------------------------------- banner

    private static final String LOGO = """
            &r
            &d██╗░░░░░░█████╗░██████╗░██████╗░██╗░░░██╗  &5░█████╗░░█████╗░██████╗░███████╗
            &d██║░░░░░██╔══██╗██╔══██╗██╔══██╗╚██╗░██╔╝  &5██╔══██╗██╔══██╗██╔══██╗██╔════╝
            &d██║░░░░░██║░░██║██████╦╝██████╦╝░╚████╔╝░  &5██║░░╚═╝██║░░██║██████╔╝█████╗░░
            &d██║░░░░░██║░░██║██╔══██╗██╔══██╗░░╚██╔╝░░  &5██║░░██╗██║░░██║██╔══██╗██╔══╝░░
            &d███████╗╚█████╔╝██████╦╝██████╦╝░░░██║░░░  &5╚█████╔╝╚█████╔╝██║░░██║███████╗
            &d╚══════╝░╚════╝░╚═════╝░╚═════╝░░░░╚═╝░░░  &5░╚════╝░░╚════╝░╚═╝░░╚═╝╚══════╝
            &r""";

    public static void logLoad() {
        console(LOGO + """

                  &7Developed by &dLorenz-Development &7for &dVenrixMC
                  &7Ogni progetto di questo Development e riservato e coperto da copyright.
                  &7Per usarlo contattami su discord: &dvanixy

                                &d&lLOADING, PLEASE WAIT...
                """);
    }

    public static void logStart(int modules) {
        console(LOGO + "\n&7Moduli attivi: &d" + modules + "\n&fStatus: &aEnabled\n");
    }

    public static void logStop() {
        console(LOGO + "\n&fStatus: &cDisabled\n");
    }
}
