package dev.lorenzzz.lobbycore.module.modules.announcement;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.util.Sounds;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rotating messages in lobby chat. Each entry is a list of action lines, so an announcement
 * can be several chat lines, a title and a sound together rather than one string.
 *
 * <p>Order is configurable because both readings are reasonable: a fixed rotation reads as a
 * schedule and is easier to predict, shuffling avoids everyone learning the cycle. The default
 * is the fixed one.
 */
public final class AnnouncementModule extends AbstractModule {

    private final List<List<String>> announcements = new ArrayList<>();
    private int cursor;

    private boolean shuffle;
    private String sound;
    private float volume;
    private float pitch;

    public AnnouncementModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "announcements"; }
    @Override public String configFile() { return "announcements"; }

    @Override
    public String description() {
        return "Annunci automatici in chat";
    }

    @Override
    public void onEnable() {
        var config = config();
        String root = "announcements.";

        long intervalSeconds = Math.max(5L, config.getLong(root + "interval_seconds", 180L));
        shuffle = config.getBoolean(root + "shuffle", false);
        sound = config.getString(root + "sound.name", "");
        volume = (float) config.getDouble(root + "sound.volume", 1.0);
        pitch = (float) config.getDouble(root + "sound.pitch", 1.0);

        announcements.clear();
        ConfigurationSection messages = config.getConfigurationSection(root + "messages");
        if (messages != null) {
            for (String key : messages.getKeys(false)) {
                List<String> lines = messages.getStringList(key);
                if (!lines.isEmpty()) announcements.add(lines);
            }
        }
        if (shuffle) Collections.shuffle(announcements);
        cursor = 0;

        if (announcements.isEmpty()) return;
        repeat(this::broadcast, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    @Override
    protected void shutdown() {
        announcements.clear();
    }

    private void broadcast() {
        if (announcements.isEmpty()) return;

        List<String> lines = announcements.get(cursor);
        cursor = (cursor + 1) % announcements.size();
        // Reshuffle at the end of a pass rather than picking at random each time: random picking
        // repeats the same announcement back to back often enough to look broken.
        if (shuffle && cursor == 0) Collections.shuffle(announcements);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!inLobbyWorld(player.getWorld())) continue;
            managers.actions().execute(player, lines);
            if (sound != null && !sound.isEmpty()) Sounds.play(player, sound, volume, pitch);
        }
    }
}
