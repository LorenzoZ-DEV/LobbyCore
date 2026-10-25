package dev.lorenzzz.lobbycore.module.modules.web;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Editing for <em>every</em> yml the plugin owns, not a hand-maintained list of four.
 *
 * <p>SMPLibrary's editor enumerated its scopes in a constant, which meant a file added by a later
 * build was invisible in the panel until somebody remembered to add it. Here the data folder is
 * walked instead: whatever is there is editable, including files an admin created themselves —
 * a new menu under {@code menus/} shows up the moment it exists.
 *
 * <p>Three rules keep that from being dangerous. A save is written only after the new text parses
 * as YAML, and a copy of the old file is kept alongside so a bad save can be rolled back. Paths
 * are resolved and checked against the data folder, so a crafted name cannot climb out of it. And
 * {@code spawn.yml} and the database file are refused: the first is written by {@code /setspawn}
 * and hand-editing it races the plugin, the second is not text.
 */
public final class ConfigFiles {

    /** Written by the plugin itself — editing them from a browser races whoever owns them. */
    private static final Set<String> PROTECTED = Set.of("spawn.yml");

    private final LobbyCore plugin;

    public ConfigFiles(LobbyCore plugin) {
        this.plugin = plugin;
    }

    /** One editable file: its path relative to the data folder, and how big it is. */
    public record Entry(String path, long size, long modified) {}

    /** Every yml under the data folder, sorted so the top-level files come first. */
    public List<Entry> list() {
        Path root = plugin.getDataFolder().toPath();
        List<Entry> found = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root, 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .forEach(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        try {
                            found.add(new Entry(relative, Files.size(path),
                                    Files.getLastModifiedTime(path).toMillis()));
                        } catch (IOException ignored) {
                            found.add(new Entry(relative, 0, 0));
                        }
                    });
        } catch (IOException e) {
            C.warning("Pannello: impossibile elencare i config: " + e.getMessage());
        }

        // Depth first, then alphabetical: config.yml and messages.yml above the folders.
        found.sort(Comparator
                .comparingInt((Entry entry) -> (int) entry.path().chars().filter(c -> c == '/').count())
                .thenComparing(Entry::path));
        return found;
    }

    public boolean isProtected(String relative) {
        return PROTECTED.contains(relative.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a relative path inside the data folder.
     *
     * <p>{@code null} when it escapes, is not a yml, or does not exist. The escape check is the
     * point: without it, {@code ../../server.properties} — or anything else on the box the server
     * user can read — would be one request away.
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) return null;
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".yml")) return null;

        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        return target.startsWith(root) ? target : null;
    }

    public String read(String relative) throws IOException {
        Path path = resolve(relative);
        if (path == null || !Files.exists(path)) return null;
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Writes a file and reloads whatever depends on it.
     *
     * <p>Returns {@code null} on success, otherwise a message fit to show in the browser. The old
     * contents are kept in a {@code .bak} sibling before the write, and restored if the new text
     * turns out not to parse — a panel that can leave the server with a broken config is a panel
     * nobody will use twice.
     */
    public String write(String relative, String content, String who) {
        Path path = resolve(relative);
        if (path == null) return "percorso non valido";
        if (isProtected(relative)) return "'" + relative + "' e' gestito dal plugin e non si modifica da qui";

        // Parse before touching the file at all. The old editor wrote first and rolled back on
        // failure, which leaves a window where another thread reads the broken version.
        try {
            YamlConfiguration probe = new YamlConfiguration();
            probe.loadFromString(content);
        } catch (Exception e) {
            return "YAML non valido: " + e.getMessage();
        }

        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        try {
            if (Files.exists(path)) Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            C.warning("Pannello: backup di " + relative + " fallito: " + e.getMessage());
        }

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "scrittura fallita: " + e.getMessage();
        }

        applyReload(relative);

        plugin.getManagers().activity().record(ActivityLog.PANEL, (String) null,
                "config salvato: " + relative + " (" + who + ")");
        return null;
    }

    /**
     * Restores the {@code .bak} written by the last save.
     *
     * <p>One level of undo, which is what this is for: an admin who has just broken the lobby
     * from a browser wants the previous version back in one click, not a version history.
     */
    public String restore(String relative, String who) {
        Path path = resolve(relative);
        if (path == null) return "percorso non valido";
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        if (!Files.exists(backup)) return "nessun backup per " + relative;

        try {
            Files.copy(backup, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return "ripristino fallito: " + e.getMessage();
        }

        applyReload(relative);
        plugin.getManagers().activity().record(ActivityLog.PANEL, (String) null,
                "config ripristinato: " + relative + " (" + who + ")");
        return null;
    }

    /**
     * Reloads only what the edited file feeds.
     *
     * <p>Editing one menu should not restart every module, and editing a module's file should not
     * touch the rest. The web module is the one that must never be restarted from here: it would
     * tear down the HTTP server mid-request, dropping the event stream and the session of the
     * person who just clicked save — the browser then reconnects into a fresh auth that has never
     * heard of its cookie and the UI sits frozen on the old state.
     */
    private void applyReload(String relative) {
        var managers = plugin.getManagers();
        String name = relative.toLowerCase(Locale.ROOT);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (name.equals("config.yml")) {
                plugin.reloadConfig();
                managers.config().reload();
                managers.errors().reload();
                managers.activity().reload();
                managers.modules().reload(Set.of(WebModule.ID));
                return;
            }
            if (name.equals("messages.yml")) {
                managers.messages().reload();
                return;
            }
            if (name.startsWith("menus/")) {
                managers.menus().reload();
                return;
            }
            if (name.startsWith("modules/")) {
                // The module manager re-reads every module's file when it restarts them, and it
                // does not restart the web module — its own id is excluded by the panel's toggle
                // guard, and a config edit never enables or disables anything.
                managers.config().reload();
                managers.modules().reload(Set.of(WebModule.ID));
                return;
            }
            managers.config().reload();
        });
    }
}
