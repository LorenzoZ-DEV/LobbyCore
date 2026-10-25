package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Owns config.yml and every file under {@code modules/} and {@code menus/}.
 *
 * <p>Each module gets its own file rather than a section of one big config, which is how the
 * DeluxeHub-shaped configs this plugin inherits are already laid out. The trade-off is that a
 * module added in a later build has no file on an existing install; {@link #module(String)}
 * handles that by writing the bundled default on first access.
 *
 * <p>Files on disk are merged with the bundled defaults on load, so an option introduced by an
 * update appears without the admin having to delete their file. The merge only adds missing
 * keys — it never overwrites a value the admin set.
 *
 * <p>When defaults change incompatibly (renamed keys, new layouts), the bundled file carries a
 * higher {@code config-version}: the disk file is then backed up next to itself and replaced
 * with the bundled one, and the console says so. Same-jar rebuilds never bump versions, so
 * day-to-day restarts never touch anything.
 */
public final class ConfigManager implements Manager {

    /** Top-level key every bundled yml carries; bump it when that file's defaults change. */
    public static final String VERSION_KEY = "config-version";

    private final LobbyCore plugin;
    private YamlConfiguration config;
    private final Map<String, YamlConfiguration> modules = new LinkedHashMap<>();

    public ConfigManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "config"; }

    @Override
    public void enable() {
        plugin.saveDefaultConfig();
        // Both folders are written out in full on first run, not lazily. A module's file would
        // otherwise only appear once the module is switched on, which is backwards: an admin
        // wants to read and edit the config before deciding to enable it.
        extractFolder("modules/");
        extractFolder("menus/");
        reload();
    }

    /**
     * Copies every bundled resource under a folder into the data folder, skipping ones the admin
     * already has.
     *
     * <p>Bukkit's {@code saveResource} only takes one path at a time and there is no API for
     * listing a jar's contents, so the jar is opened and walked directly. Reading our own code
     * source is the only way to find out what we shipped.
     */
    private void extractFolder(String prefix) {
        URL source = getClass().getProtectionDomain().getCodeSource().getLocation();
        if (source == null) return;

        try (JarFile jar = new JarFile(new File(source.toURI()))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".yml")) continue;
                if (new File(plugin.getDataFolder(), name).exists()) continue;
                plugin.saveResource(name, false);
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            // Running from an exploded classpath (an IDE, mostly). The lazy path in
            // module() still writes each file the first time it is asked for.
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                C.warning("Impossibile estrarre " + prefix + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void disable() {
        config = null;
        modules.clear();
    }

    @Override
    public void reload() {
        config = load("config.yml");
        modules.clear();
    }

    public YamlConfiguration raw() {
        return config;
    }

    public String serverName() {
        return config.getString("settings.server-name", "lobby-1");
    }

    public boolean debug() {
        return config.getBoolean("settings.debug", false);
    }

    /** Whether the named module is switched on under the {@code modules:} block of config.yml. */
    public boolean isEnabled(String moduleId) {
        return config != null && config.getBoolean("modules." + moduleId, false);
    }

    public ConfigurationSection moduleToggles() {
        return config == null ? null : config.getConfigurationSection("modules");
    }

    /**
     * The config of one module, loaded from {@code modules/<name>.yml} on first access and kept
     * until the next {@link #reload()}.
     */
    public YamlConfiguration module(String fileName) {
        return modules.computeIfAbsent(fileName, name -> load("modules/" + name + ".yml"));
    }

    /**
     * Reads a file from the data folder, writing the bundled copy first if it is missing, then
     * merging in any keys the bundled copy has and the file does not.
     *
     * <p>If the bundled file carries a newer {@code config-version} than the disk one, the disk
     * file is backed up as {@code <name>.v<old>.bak} and replaced with the bundled copy: that is
     * how changed defaults (not just new keys) reach existing installs. Files with no bundled
     * counterpart — e.g. menus the admin created themselves — pass through untouched.
     */
    public YamlConfiguration load(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists() && plugin.getResource(path) != null) {
            plugin.saveResource(path, false);
        }

        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration bundled = bundled(path);
        if (bundled == null) return disk;

        int diskVersion = disk.getInt(VERSION_KEY, 0);
        int bundledVersion = bundled.getInt(VERSION_KEY, 0);
        if (bundledVersion > diskVersion) {
            File backup = new File(plugin.getDataFolder(), path + ".v" + diskVersion + ".bak");
            try {
                java.nio.file.Files.copy(file.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                C.warning("Impossibile salvare il backup di " + path + ": " + e.getMessage());
            }
            plugin.saveResource(path, true);
            C.warning(path + " aggiornato alla v" + bundledVersion
                    + " (backup: " + backup.getName() + "). Controlla il backup se avevi modifiche tue.");
            return YamlConfiguration.loadConfiguration(file);
        }

        boolean changed = false;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || disk.contains(key)) continue;
            disk.set(key, bundled.get(key));
            // Con il valore viaggia anche il suo commento: questi file sono documentati riga
            // per riga, e un'opzione nuova che compare nuda in mezzo alle altre non si capisce.
            disk.setComments(key, bundled.getComments(key));
            disk.setInlineComments(key, bundled.getInlineComments(key));
            changed = true;
        }

        if (changed) {
            try {
                disk.save(file);
                C.info("Aggiornato &f" + path + " &7con le nuove opzioni di questa versione.");
            } catch (IOException e) {
                C.warning("Impossibile salvare " + path + ": " + e.getMessage());
            }
        }
        return disk;
    }

    private YamlConfiguration bundled(String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
