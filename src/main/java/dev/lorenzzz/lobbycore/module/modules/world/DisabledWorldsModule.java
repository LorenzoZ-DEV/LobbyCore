package dev.lorenzzz.lobbycore.module.modules.world;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which worlds the rest of the plugin acts in.
 *
 * <p>Two readings of the same list, picked by {@code invert}: with it off the named worlds are
 * the ones lobby behaviour is <em>disabled</em> in (a build world next to the lobby); with it on
 * they are the only worlds it is <em>enabled</em> in, which is what a box hosting several
 * unrelated worlds wants.
 *
 * <p>The module does not listen to anything. It installs a predicate on the manager service and
 * removes it again on stop, so every other module reads one answer and nothing has to know this
 * module exists.
 */
public final class DisabledWorldsModule extends AbstractModule {

    private final Set<String> worlds = new HashSet<>();
    private boolean invert;

    public DisabledWorldsModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "disabled-worlds"; }
    @Override public String configFile() { return "disabled_worlds"; }

    @Override
    public String description() {
        return "Limita i moduli della lobby ai mondi giusti";
    }

    @Override
    public void onEnable() {
        worlds.clear();
        invert = config().getBoolean("disabled-worlds.invert", false);
        for (String name : config().getStringList("disabled-worlds.worlds")) {
            worlds.add(name.toLowerCase(Locale.ROOT));
        }
        managers.worldFilter(this::active);
    }

    @Override
    protected void shutdown() {
        // Back to "everywhere" rather than to whatever was there before: this module is the only
        // thing that ever sets a filter, so the default is the correct restore.
        managers.worldFilter(null);
        worlds.clear();
    }

    private boolean active(World world) {
        if (world == null) return false;
        boolean listed = worlds.contains(world.getName().toLowerCase(Locale.ROOT));
        return invert == listed;
    }
}
