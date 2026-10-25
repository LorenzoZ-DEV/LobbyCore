package dev.lorenzzz.lobbycore.manager;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.PaperCommandManager;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.command.GamemodeCommands;
import dev.lorenzzz.lobbycore.command.LobbyCommand;
import org.bukkit.Bukkit;

import java.util.Locale;

/**
 * Owns Aikar's Command Framework. Modules register their own command classes through
 * {@link #register}, and the ones that belong to the core are registered here.
 */
public final class CommandManager implements Manager {

    private final LobbyCore plugin;
    private PaperCommandManager acf;

    public CommandManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "commands"; }

    @Override
    public void enable() {
        acf = new PaperCommandManager(plugin);

        // The brigadier unstable API is deliberately not enabled: it registers a listener on
        // CommandRegisteredEvent, which Paper deprecated in the 1.21.11 line. ACF's own
        // completion handler still gives tab-completion, just server-side.
        acf.enableUnstableAPI("help");
        acf.getLocales().setDefaultLocale(Locale.ITALIAN);

        registerCompletions();

        register(new LobbyCommand(plugin));
        register(new GamemodeCommands(plugin));
    }

    private void registerCompletions() {
        acf.getCommandCompletions().registerCompletion("modules", context ->
                plugin.getManagers().modules().ids());

        acf.getCommandCompletions().registerCompletion("menus", context ->
                plugin.getManagers().menus().ids());

        acf.getCommandCompletions().registerCompletion("networkplayers", context -> {
            var names = new java.util.ArrayList<String>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            names.addAll(plugin.getManagers().redis().networkPlayers());
            return names;
        });
    }

    public PaperCommandManager acf() {
        return acf;
    }

    public void register(BaseCommand... toRegister) {
        if (acf == null) return;
        for (BaseCommand command : toRegister) {
            try {
                acf.registerCommand(command);
            } catch (Exception e) {
                C.warning("Comando " + command.getClass().getSimpleName()
                        + " non registrato: " + e.getMessage());
            }
        }
    }

    public void unregister(BaseCommand... toUnregister) {
        if (acf == null) return;
        for (BaseCommand command : toUnregister) {
            acf.unregisterCommand(command);
        }
    }

    @Override
    public void disable() {
        if (acf != null) {
            acf.unregisterCommands();
            acf = null;
        }
    }
}
