package dev.lorenzzz.lobbycore.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code %animation:name%} placeholder shared by the scoreboard and the tablist, in the shape
 * TAB defines it:
 *
 * <pre>
 * logo:
 *   change-interval: 100   # milliseconds per frame
 *   texts: ["frame 1", "frame 2"]
 * </pre>
 *
 * <p>Frames are picked from the wall clock rather than advanced by a task. An animation with a
 * 100 ms interval would otherwise need its own 2-tick timer, and a dozen of them would need a
 * dozen; deriving the frame from {@code now / interval} gives the same result for free, keeps
 * every player on the same frame, and survives a scoreboard refresh landing late.
 */
public final class Animations {

    /** One animation: its frames and how long each is shown. */
    private record Animation(List<String> frames, long intervalMillis) {
        String frame(long now) {
            if (frames.isEmpty()) return "";
            long index = (now / Math.max(1, intervalMillis)) % frames.size();
            return frames.get((int) index);
        }
    }

    private final Map<String, Animation> animations = new ConcurrentHashMap<>();

    /** Lo stesso pattern di {@link #apply}: i tag vanno protetti prima di PlaceholderAPI. */
    private static final Pattern TAG = Pattern.compile("%animation:([^%]*)%");

    /**
     * Testo con i tag nascosti a PlaceholderAPI, da ripristinare con
     * {@link Guarded#restore(String)} dopo la risoluzione.
     *
     * <p>Serve perché un'altra expansion puo dichiarare lo stesso prefisso
     * {@code animation:} (es. il plugin TAB, da cui molte config migrano): senza questa
     * protezione PlaceholderAPI risolverebbe i frame con i tempi dell'altro plugin e le
     * nostre animazioni sembrerebbero lente o a scatti pur con un redraw veloce.
     */
    public record Guarded(String text, List<String> names) {
        public String restore(String processed) {
            String out = processed;
            for (int index = 0; index < names.size(); index++) {
                out = out.replace("\u0000" + index + "\u0000", "%animation:" + names.get(index) + "%");
            }
            return out;
        }
    }

    /**
     * Il testo senza i tag {@code %animation:nome%}, per capire se quello che resta ha ancora
     * placeholder veri da risolvere.
     *
     * <p>Un frame di animazione e uguale per tutti e cambia ad ogni disegno: una riga fatta
     * solo di animazioni non deve ne finire in cache ne far scattare il percorso "per singolo
     * giocatore". Sensibile alle maiuscole come {@link #apply} e {@link #guard}, cosi i tre
     * concordano sempre su cosa sia un tag.
     */
    public static String without(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf("%animation:") < 0) return text;
        return TAG.matcher(text).replaceAll("");
    }

    /** Nasconde ogni {@code %animation:nome%} dietro un segnaposto che PAPI non tocca. */
    public static Guarded guard(String text) {
        if (text == null || text.indexOf("%animation:") < 0) {
            return new Guarded(text, List.of());
        }
        List<String> names = new ArrayList<>();
        StringBuilder out = new StringBuilder(text.length());
        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
            matcher.appendReplacement(out, "\u0000" + (names.size() - 1) + "\u0000");
        }
        matcher.appendTail(out);
        return new Guarded(out.toString(), List.copyOf(names));
    }

    /** Replaces the loaded set with the contents of an animations file. */
    public void load(YamlConfiguration config) {
        animations.clear();
        if (config == null) return;

        for (String key : config.getKeys(false)) {
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null) continue;
            List<String> frames = section.getStringList("texts");
            if (frames.isEmpty()) continue;
            animations.put(key, new Animation(List.copyOf(frames),
                    section.getLong("change-interval", 1000L)));
        }
    }

    public boolean isEmpty() {
        return animations.isEmpty();
    }

    public int size() {
        return animations.size();
    }

    /**
     * Substitutes every {@code %animation:name%} in the text. Returns the input unchanged when it
     * holds none, which is the common case and skips building a StringBuilder for it.
     */
    public String apply(String text) {
        if (text == null || text.isEmpty() || animations.isEmpty()) return text;
        int start = text.indexOf("%animation:");
        if (start < 0) return text;

        long now = System.currentTimeMillis();
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;

        while (start >= 0) {
            int end = text.indexOf('%', start + 11);
            if (end < 0) break;

            out.append(text, cursor, start);
            String name = text.substring(start + 11, end);
            Animation animation = animations.get(name);
            // An unknown name is left as written, so a typo shows up in game instead of
            // silently blanking the line.
            out.append(animation == null ? text.substring(start, end + 1) : animation.frame(now));

            cursor = end + 1;
            start = text.indexOf("%animation:", cursor);
        }

        out.append(text, cursor, text.length());
        return out.toString();
    }
}
