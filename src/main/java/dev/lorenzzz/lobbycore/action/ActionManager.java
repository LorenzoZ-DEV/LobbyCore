package dev.lorenzzz.lobbycore.action;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.Manager;
import dev.lorenzzz.lobbycore.util.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code [ACTION] argument} vocabulary every config list in the plugin speaks — join events,
 * hotbar item clicks, menu buttons. This is DeluxeHub's action syntax, so an existing DeluxeHub
 * config drops straight in.
 *
 * <p>Actions are run in order, which matters for {@code [DELAY]}: rather than sleeping, it
 * schedules the <em>rest</em> of the list and returns, so a list can stage a sequence without any
 * caller knowing it did.
 */
public final class ActionManager implements Manager {

    /** The plugin channel BungeeCord/Velocity listens on for server switches. */
    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private final LobbyCore plugin;
    private final Map<String, Action> actions = new HashMap<>();

    public ActionManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "actions"; }

    @Override
    public void enable() {
        register("MESSAGE", (core, player, argument) -> player.sendMessage(C.mm(argument)));

        register("BROADCAST", (core, player, argument) -> {
            Component message = C.mm(argument);
            for (Player online : Bukkit.getOnlinePlayers()) online.sendMessage(message);
        });

        register("ACTIONBAR", (core, player, argument) -> player.sendActionBar(C.mm(argument)));

        register("TITLE", (core, player, argument) -> {
            // title;subtitle;fadeIn;stay;fadeOut — everything after the subtitle optional.
            String[] parts = argument.split(";", 5);
            Times times = Times.parse(parts, 2);
            player.showTitle(Title.title(
                    C.mm(parts[0]),
                    parts.length > 1 ? C.mm(parts[1]) : Component.empty(),
                    Title.Times.times(times.fadeIn, times.stay, times.fadeOut)));
        });

        register("SOUND", (core, player, argument) -> {
            // SOUND_NAME;volume;pitch — volume and pitch optional.
            String[] parts = argument.split(";");
            float volume = parts.length > 1 ? parseFloat(parts[1], 1f) : 1f;
            float pitch = parts.length > 2 ? parseFloat(parts[2], 1f) : 1f;
            Sounds.play(player, parts[0], volume, pitch);
        });

        register("PLAYER_CMD", (core, player, argument) -> player.performCommand(argument));
        register("COMMAND", (core, player, argument) -> player.performCommand(argument));

        register("CONSOLE_CMD", (core, player, argument) ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), argument));
        register("CONSOLE", (core, player, argument) ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), argument));

        register("GAMEMODE", (core, player, argument) -> {
            GameMode mode = gameMode(argument);
            if (mode != null) player.setGameMode(mode);
        });

        register("EFFECT", (core, player, argument) -> {
            // TYPE;amplifier — infinite duration, since lobby effects are meant to persist.
            String[] parts = argument.split(";");
            PotionEffectType type = potionEffect(parts[0]);
            if (type == null) return;
            int amplifier = parts.length > 1 ? (int) parseFloat(parts[1], 1f) : 1;
            // Amplifier is zero-based in the API but configs write "SPEED;1" meaning Speed I.
            player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION,
                    Math.max(0, amplifier - 1), false, false, false));
        });

        register("CLOSE", (core, player, argument) -> player.closeInventory());

        /*
         * Pushes the existing chat off the screen before a welcome block. The alternative — and
         * what the configs this replaces actually did — is forty consecutive blank [MESSAGE]
         * entries, which is unreadable in YAML and impossible to adjust.
         */
        register("CLEARCHAT", (core, player, argument) -> {
            int lines = argument.isBlank() ? 100 : (int) parseFloat(argument, 100f);
            Component blank = Component.empty();
            for (int index = 0; index < Math.max(1, Math.min(200, lines)); index++) {
                player.sendMessage(blank);
            }
        });

        register("MENU", (core, player, argument) -> core.getManagers().menus().open(player, argument.trim()));

        register("TELEPORT", (core, player, argument) -> {
            Location location = location(argument);
            if (location != null) player.teleportAsync(location);
        });

        register("SPAWN", (core, player, argument) -> player.performCommand("spawn"));

        register("PROXY", ActionManager::sendToServer);
        register("SERVER", ActionManager::sendToServer);
        register("CONNECT", ActionManager::sendToServer);

        // DELAY is handled inside executeActions — it needs the rest of the list, which an
        // Action never sees. Registered anyway so an unknown-action warning is not logged for it.
        register("DELAY", (core, player, argument) -> {});
    }

    @Override
    public void disable() {
        actions.clear();
    }

    public void register(String identifier, Action action) {
        actions.put(identifier.toUpperCase(Locale.ROOT), action);
    }

    public boolean has(String identifier) {
        return actions.containsKey(identifier.toUpperCase(Locale.ROOT));
    }

    /** Runs a whole list in order, honouring {@code [DELAY] <ticks>}. */
    public void execute(Player player, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        run(player, lines, 0);
    }

    private void run(Player player, List<String> lines, int from) {
        for (int index = from; index < lines.size(); index++) {
            if (!player.isOnline()) return;
            String line = lines.get(index);
            if (line == null) continue;

            String identifier = identifier(line);
            if (identifier == null) {
                // A bare line with no [TAG] is treated as a message: it is what an admin writing
                // "- ''" for a blank spacer means, and the join configs are full of them.
                player.sendMessage(C.mm(resolve(line, player)));
                continue;
            }

            if (identifier.equals("DELAY")) {
                long ticks = (long) parseFloat(argument(line), 20f);
                int resume = index + 1;
                if (resume >= lines.size()) return;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) run(player, lines, resume);
                }, Math.max(1L, ticks));
                return;
            }

            Action action = actions.get(identifier);
            if (action == null) {
                C.warning("Azione sconosciuta: '" + line + "'");
                continue;
            }

            try {
                action.execute(plugin, player, resolve(argument(line), player));
            } catch (Exception e) {
                C.warning("L'azione '" + line + "' e fallita: " + e.getMessage());
            }
        }
    }

    /** The {@code NAME} out of {@code [NAME] rest}, or null when the line has no tag. */
    private static String identifier(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '[') return null;
        int close = trimmed.indexOf(']');
        return close <= 1 ? null : trimmed.substring(1, close).toUpperCase(Locale.ROOT);
    }

    /** Everything after {@code [NAME]}, trimmed of the single separating space. */
    private static String argument(String line) {
        String trimmed = line.stripLeading();
        int close = trimmed.indexOf(']');
        if (close < 0 || close + 1 >= trimmed.length()) return "";
        String rest = trimmed.substring(close + 1);
        return rest.startsWith(" ") ? rest.substring(1) : rest;
    }

    private String resolve(String text, Player player) {
        return C.replace(text, player, Map.of("%player%", player.getName()));
    }

    /**
     * Asks the proxy to move the player. The message goes on the player's own plugin channel,
     * which is why there is no fallback: a lobby that is not behind a proxy has nowhere to send
     * anyone, and silently doing nothing is the honest outcome.
     */
    private static void sendToServer(LobbyCore plugin, Player player, String server) {
        if (server == null || server.isBlank()) return;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF("Connect");
            out.writeUTF(server.trim());
        } catch (IOException e) {
            C.warning("Impossibile costruire il messaggio di switch verso " + server);
            return;
        }
        plugin.getManagers().activity().record(
                dev.lorenzzz.lobbycore.manager.ActivityLog.SWITCH, player, server.trim());
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, buffer.toByteArray());
    }

    private static GameMode gameMode(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "survival", "s", "0" -> GameMode.SURVIVAL;
            case "creative", "c", "1" -> GameMode.CREATIVE;
            case "adventure", "a", "2" -> GameMode.ADVENTURE;
            case "spectator", "sp", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    /** Potion types moved to a registry in 1.21; the old {@code getByName} is gone. */
    public static PotionEffectType potionEffect(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Registry.EFFECT.get(NamespacedKey.minecraft(
                    name.trim().toLowerCase(Locale.ROOT)));
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code world;x;y;z[;yaw;pitch]}. */
    private static Location location(String argument) {
        String[] parts = argument.split(";");
        if (parts.length < 4) return null;
        World world = Bukkit.getWorld(parts[0].trim());
        if (world == null) return null;
        Location location = new Location(world,
                parseFloat(parts[1], 0f), parseFloat(parts[2], 64f), parseFloat(parts[3], 0f));
        if (parts.length >= 6) {
            location.setYaw(parseFloat(parts[4], 0f));
            location.setPitch(parseFloat(parts[5], 0f));
        }
        return location;
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    /** Title timings in ticks, with the vanilla 10/70/20 default when they are left out. */
    private record Times(Duration fadeIn, Duration stay, Duration fadeOut) {
        static Times parse(String[] parts, int offset) {
            long fadeIn = parts.length > offset ? (long) parseFloat(parts[offset], 10f) : 10L;
            long stay = parts.length > offset + 1 ? (long) parseFloat(parts[offset + 1], 70f) : 70L;
            long fadeOut = parts.length > offset + 2 ? (long) parseFloat(parts[offset + 2], 20f) : 20L;
            return new Times(ticks(fadeIn), ticks(stay), ticks(fadeOut));
        }

        private static Duration ticks(long ticks) {
            return Duration.ofMillis(ticks * 50L);
        }
    }
}
