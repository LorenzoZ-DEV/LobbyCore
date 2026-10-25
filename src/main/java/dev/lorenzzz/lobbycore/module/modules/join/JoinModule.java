package dev.lorenzzz.lobbycore.module.modules.join;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.PlayerDataManager;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.module.modules.spawn.SpawnModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What happens the moment somebody walks into the lobby: the greeting in chat, the welcome block
 * of messages, the state reset, and the firework on a first join.
 *
 * <p>The join and quit lines are taken over from the server's own, not added alongside them —
 * {@code event.joinMessage(null)} suppresses the vanilla one and this module sends its own, so a
 * quit line configured as empty really means silence rather than a double message.
 */
public final class JoinModule extends AbstractModule implements Listener {

    private YamlConfiguration messages;

    private boolean spawnOnJoin;
    private boolean heal;
    private boolean extinguish;
    private boolean clearInventory;
    private boolean clearEffects;
    private boolean resetExperience;

    private boolean fireworkEnabled;
    private boolean fireworkFirstJoinOnly;
    private int fireworkPower;
    private boolean fireworkFlicker;
    private boolean fireworkTrail;
    private FireworkEffect.Type fireworkType;
    private final List<Color> fireworkColors = new ArrayList<>();

    private String joinMessage;
    private String quitMessage;
    private String firstJoinMessage;
    private List<String> joinActions;
    private List<String> firstJoinActions;

    public JoinModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "join"; }
    @Override public String configFile() { return "join_settings"; }

    @Override
    public String description() {
        return "Messaggi, azioni e reset di stato all'entrata";
    }

    @Override
    public void onEnable() {
        var config = config();
        String root = "join_settings.";

        spawnOnJoin = config.getBoolean(root + "spawn_join", true);
        heal = config.getBoolean(root + "heal", true);
        extinguish = config.getBoolean(root + "extinguish", true);
        clearInventory = config.getBoolean(root + "clear_inventory", false);
        clearEffects = config.getBoolean(root + "clear_effects", true);
        resetExperience = config.getBoolean(root + "reset_experience", true);

        fireworkEnabled = config.getBoolean(root + "firework.enabled", false);
        fireworkFirstJoinOnly = config.getBoolean(root + "firework.first_join_only", true);
        fireworkPower = Math.max(0, Math.min(3, config.getInt(root + "firework.power", 1)));
        fireworkFlicker = config.getBoolean(root + "firework.flicker", true);
        fireworkTrail = config.getBoolean(root + "firework.trail", true);
        fireworkType = fireworkType(config.getString(root + "firework.type", "BALL_LARGE"));

        fireworkColors.clear();
        for (String name : config.getStringList(root + "firework.colors")) {
            Color color = color(name);
            if (color != null) fireworkColors.add(color);
        }
        if (fireworkColors.isEmpty()) fireworkColors.add(Color.WHITE);

        // The greeting lives in its own file, kept separate from the state-reset switches
        // because it is the one an admin edits weekly.
        messages = managers.config().module("join_leave_messages");
        joinMessage = messages.getString("join_leave_messages.join_message", "");
        quitMessage = messages.getString("join_leave_messages.quit_message", "");
        firstJoinMessage = messages.getString("join_leave_messages.first_join_message", "");
        joinActions = messages.getStringList("join_events");
        firstJoinActions = messages.getStringList("first_join_events");

        listen(this);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!inLobbyWorld(player.getWorld())) return;

        PlayerDataManager.LobbyPlayer data = managers.playerData().get(player);
        boolean firstJoin = data != null && data.firstJoinEver();

        event.joinMessage(null);
        broadcast(firstJoin && !firstJoinMessage.isEmpty() ? firstJoinMessage : joinMessage, player);

        reset(player);

        if (spawnOnJoin) {
            SpawnModule spawn = managers.modules().module("spawn", SpawnModule.class);
            if (spawn != null && spawn.teleportOnJoin()) spawn.send(player);
        }

        List<String> actions = firstJoin && !firstJoinActions.isEmpty() ? firstJoinActions : joinActions;
        if (!actions.isEmpty()) managers.actions().execute(player, actions);

        if (fireworkEnabled && (!fireworkFirstJoinOnly || firstJoin)) {
            // One tick after the spawn teleport, or the firework spawns where the player was.
            later(() -> {
                if (player.isOnline()) launchFirework(player.getLocation());
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        // Only take the vanilla message over in a world we actually own — suppressing it in a
        // build world this module never greets anyone in would just delete the line.
        if (!inLobbyWorld(event.getPlayer().getWorld())) return;
        event.quitMessage(null);
        broadcast(quitMessage, event.getPlayer());
    }

    private void broadcast(String template, Player player) {
        if (template == null || template.isEmpty()) return;
        Component message = C.parse(template, player, Map.of(
                "%player%", player.getName(),
                "%displayname%", player.getName()));
        for (Player online : plugin.getServer().getOnlinePlayers()) online.sendMessage(message);
    }

    /** Puts the player back into a clean lobby state. */
    private void reset(Player player) {
        if (heal) {
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) player.setHealth(maxHealth.getValue());
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
        if (extinguish) player.setFireTicks(0);
        if (clearInventory) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
        }
        if (clearEffects) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
        }
        if (resetExperience) {
            player.setLevel(0);
            player.setExp(0f);
        }
    }

    private void launchFirework(Location location) {
        if (location.getWorld() == null) return;
        Firework firework = location.getWorld().spawn(location, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(fireworkType)
                .withColor(fireworkColors)
                .flicker(fireworkFlicker)
                .trail(fireworkTrail)
                .build());
        meta.setPower(fireworkPower);
        firework.setFireworkMeta(meta);
    }

    private static FireworkEffect.Type fireworkType(String name) {
        try {
            return FireworkEffect.Type.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FireworkEffect.Type.BALL_LARGE;
        }
    }

    /**
     * Colour names the way the config writes them ({@code AQUA}, {@code RED}), falling back to
     * a {@code #RRGGBB} hex so an admin is not limited to the fifteen constants.
     */
    private static Color color(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        if (trimmed.startsWith("#") && trimmed.length() == 7) {
            try {
                return Color.fromRGB(Integer.parseInt(trimmed.substring(1), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return switch (trimmed.toUpperCase(Locale.ROOT)) {
            case "AQUA" -> Color.AQUA;
            case "BLACK" -> Color.BLACK;
            case "BLUE" -> Color.BLUE;
            case "FUCHSIA" -> Color.FUCHSIA;
            case "GRAY", "GREY" -> Color.GRAY;
            case "GREEN" -> Color.GREEN;
            case "LIME" -> Color.LIME;
            case "MAROON" -> Color.MAROON;
            case "NAVY" -> Color.NAVY;
            case "OLIVE" -> Color.OLIVE;
            case "ORANGE" -> Color.ORANGE;
            case "PURPLE" -> Color.PURPLE;
            case "RED" -> Color.RED;
            case "SILVER" -> Color.SILVER;
            case "TEAL" -> Color.TEAL;
            case "WHITE" -> Color.WHITE;
            case "YELLOW" -> Color.YELLOW;
            default -> null;
        };
    }
}
