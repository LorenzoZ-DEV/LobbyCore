package dev.lorenzzz.lobbycore.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;
/**
 * Sound names from config, resolved without touching {@code org.bukkit.Sound}.
 *
 * <p>Bukkit's {@code Sound} stopped being an enum in the 1.21 line and became a registry-backed
 * interface; code written against either shape breaks on the other, and every config in this
 * plugin spells sounds the old enum way ({@code BLOCK_NOTE_BLOCK_PLING}). The key-based
 * {@code playSound} overloads are stable across all of it, so the conversion happens here once:
 * {@code BLOCK_NOTE_BLOCK_PLING} -> {@code minecraft:block.note_block.pling}.
 *
 * <p>Names already written as keys ({@code minecraft:entity.player.levelup}, or a resource-pack
 * sound) pass through untouched, which also makes custom sounds work for free.
 */
public final class Sounds {

    private Sounds() {}

    private static final Cache<String, Key> KEYS = Caffeine.newBuilder().maximumSize(256).build();

    /** {@code null} when the name cannot be turned into a valid key. */
    public static Key key(String name) {
        if (name == null || name.isBlank()) return null;
        return KEYS.get(name, Sounds::convert);
    }

    private static Key convert(String name) {
        String trimmed = name.trim();
        try {
            if (trimmed.indexOf(':') >= 0 || trimmed.indexOf('.') >= 0) {
                return Key.key(trimmed.toLowerCase(Locale.ROOT));
            }
            // Prima la costante di Bukkit: BLOCK_NOTE_BLOCK_PLING -> block.note_block.pling.
            // La conversione ingenua (ogni '_' in '.') produrrebbe block.note.block.pling,
            // che non esiste — e vale per ogni suono con l'underscore nel nome
            // (note_block, experience_orb, ender_dragon, ...): non suonava mai niente.
            Key constant = soundConstantKey(trimmed);
            if (constant != null) return constant;
            return Key.key("minecraft", trimmed.toLowerCase(Locale.ROOT).replace('_', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Legge la key dalla costante omonima di {@code org.bukkit.Sound}
     * (enum prima della 1.21, costanti di interfaccia dopo): il nome e identico in
     * entrambi i casi, quindi funziona su tutte le versioni senza toccare il tipo.
     */
    private static Key soundConstantKey(String name) {
        try {
            Class<?> sounds = Class.forName("org.bukkit.Sound");
            java.lang.reflect.Field field;
            try {
                field = sounds.getField(name.trim().toUpperCase(Locale.ROOT));
            } catch (NoSuchFieldException e) {
                return null;
            }
            Object value = field.get(null);
            // instanceof e non getMethod("getKey"): dalla 1.21 la costante e un'istanza di una
            // classe di implementazione che puo non essere pubblica, e invoke() fallirebbe con
            // IllegalAccessException — facendo ricadere tutto sulla conversione ingenua, cioe di
            // nuovo block.note.block.pling. Keyed e un'interfaccia pubblica, la implementano sia
            // l'enum vecchio sia il tipo nuovo, e non pone problemi di accesso.
            if (value instanceof org.bukkit.Keyed keyed) {
                org.bukkit.NamespacedKey namespaced = keyed.getKey();
                return Key.key(namespaced.getNamespace(), namespaced.getKey());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Plays for one player only, at their own position. */
    public static void play(Player player, String name, float volume, float pitch) {
        Key key = key(name);
        if (key == null) return;
        // The String overload rather than the Sound one, for the same reason the keys are built
        // by hand: it takes a namespaced key and has not changed shape across any 1.21 build.
        player.playSound(player.getLocation(), key.asString(), volume, pitch);
    }

    /** Plays at a fixed point so everyone nearby hears it from the right direction. */
    public static void playAt(Location location, String name, float volume, float pitch) {
        Key key = key(name);
        if (key == null || location.getWorld() == null) return;
        location.getWorld().playSound(location, key.asString(), volume, pitch);
    }
}
