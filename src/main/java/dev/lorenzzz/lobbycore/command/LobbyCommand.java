package dev.lorenzzz.lobbycore.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CatchUnknown;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.manager.ErrorCapture;
import dev.lorenzzz.lobbycore.module.Module;
import dev.lorenzzz.lobbycore.module.modules.chat.ChatFilterModule;
import dev.lorenzzz.lobbycore.module.modules.web.WebModule;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The admin entry point. Everything an operator needs to do at runtime lives here, including
 * turning a single module off without restarting the server — which is what the module
 * lifecycle in {@code AbstractModule} exists to make safe.
 */
@CommandAlias("lobbycore|lc|hub-admin")
@CommandPermission("lobbycore.admin")
public final class LobbyCommand extends BaseCommand {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final LobbyCore plugin;

    public LobbyCommand(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Default
    @Description("Informazioni sul plugin")
    public void onDefault(CommandSender sender) {
        var managers = plugin.getManagers();
        C.send(sender, "&d&lLobbyCore &7v" + plugin.getPluginMeta().getVersion());
        C.send(sender, "&7Server: &f" + managers.config().serverName());
        C.send(sender, "&7Moduli attivi: &f" + managers.modules().runningCount()
                + "&7/&f" + managers.modules().ids().size());
        C.send(sender, "&7Redis: " + (managers.redis().isEnabled() ? "&aconnesso" : "&cnon attivo"));
        C.send(sender, "&7Database: " + (managers.storage().available() ? "&aok" : "&cnon disponibile"));
        C.send(sender, "&7LuckPerms: " + (managers.hooks().hasLuckPerms() ? "&aok" : "&7assente"));
        C.send(sender, "&7PlaceholderAPI: " + (C.papiEnabled() ? "&aok" : "&7assente"));
        WebModule panel = managers.modules().module("web", WebModule.class);
        C.send(sender, "&7Pannello: " + (panel != null && panel.isServing()
                ? "&a" + panel.url() : "&7non attivo"));
        C.send(sender, "&8/lobbycore reload &7- &8modules &7- &8module <id> <on|off>");
        C.send(sender, "&8/lobbycore panel &7- &8activity [player] &7- &8errors &7- &8chatlock");
    }

    @Subcommand("reload")
    @Description("Ricarica configurazioni e moduli")
    public void onReload(CommandSender sender) {
        long start = System.currentTimeMillis();
        int running = plugin.reloadEverything();
        long elapsed = System.currentTimeMillis() - start;

        plugin.getManagers().messages().send(sender, "reload-done", Map.of(
                "%modules%", running,
                "%ms%", elapsed));
        // Neither pool is torn down by a reload, and an admin who just edited those blocks needs
        // to know why nothing happened.
        C.send(sender, "&8(database e Redis non vengono riavviati: serve un restart del server)");
    }

    @Subcommand("modules")
    @Description("Elenca i moduli e il loro stato")
    public void onModules(CommandSender sender) {
        var modules = plugin.getManagers().modules();
        C.send(sender, "&d&lMODULI");
        for (Module module : modules.all()) {
            boolean running = modules.isRunning(module.id());
            C.send(sender, (running ? " &a● " : " &c● ") + "&f" + module.id()
                    + (module.description().isEmpty() ? "" : " &8- &7" + module.description()));
        }
    }

    @Subcommand("module")
    @CommandCompletion("@modules on|off")
    @Description("Accende o spegne un modulo a caldo")
    public void onModule(CommandSender sender, String id, String state) {
        boolean enable = state.equalsIgnoreCase("on") || state.equalsIgnoreCase("true");
        String error = plugin.getManagers().modules().setEnabled(id.toLowerCase(), enable);

        if (error != null) {
            C.send(sender, "&c" + error);
            return;
        }
        C.send(sender, "&aModulo &f" + id + "&a " + (enable ? "avviato" : "fermato") + ".");
        C.send(sender, "&8(la modifica non viene salvata in config.yml)");
    }

    @Subcommand("menu")
    @CommandCompletion("@menus")
    @Description("Apre un menu")
    public void onMenu(Player sender, String id) {
        if (!plugin.getManagers().menus().open(sender, id)) {
            C.send(sender, "&cMenu &f" + id + "&c inesistente.");
        }
    }

    @Subcommand("panel")
    @Description("Link e token temporaneo per il pannello web")
    public void onPanel(CommandSender sender) {
        WebModule panel = plugin.getManagers().modules().module("web", WebModule.class);
        if (panel == null || !panel.isServing()) {
            C.send(sender, "&cIl pannello non e attivo. Accendilo con &f/lobbycore module web on&c.");
            return;
        }

        String token = panel.generateTempToken();
        // The token goes in the URL because that is how the panel's login works — one exchange
        // for a session cookie — and because a staffer on a phone is not going to retype 24 hex
        // characters into a form.
        C.send(sender, "&7Pannello: &f" + panel.url() + "/?token=" + token);
        C.send(sender, "&8Valido " + panel.tempTokenMinutes() + " minuti, un solo utilizzo.");
    }

    @Subcommand("errors")
    @Description("Gli ultimi errori catturati")
    public void onErrors(CommandSender sender) {
        var errors = plugin.getManagers().errors();
        List<ErrorCapture.Issue> issues = errors.recent();
        if (issues.isEmpty()) {
            C.send(sender, "&aNessun errore catturato.");
            return;
        }

        C.send(sender, "&d&lERRORI &7(" + issues.size() + " distinti, "
                + errors.severeCount() + " gravi)");
        for (int index = 0; index < Math.min(8, issues.size()); index++) {
            ErrorCapture.Issue issue = issues.get(index);
            C.send(sender, ("SEVERE".equals(issue.level()) ? " &c● " : " &e● ")
                    + "&f" + shorten(issue.message(), 70)
                    + " &8x" + issue.count() + " &8(" + shorten(issue.source(), 28) + ")");
        }
        if (issues.size() > 8) {
            C.send(sender, "&8… altri " + (issues.size() - 8) + " nel pannello web.");
        }
    }

    @Subcommand("activity")
    @CommandCompletion("@players")
    @Description("Cosa hanno fatto i giocatori")
    public void onActivity(CommandSender sender, @Optional String playerName) {
        var activity = plugin.getManagers().activity();
        List<ActivityLog.Entry> entries = playerName == null
                ? activity.recent(15)
                : activity.forPlayer(playerName, 15);

        if (entries.isEmpty()) {
            C.send(sender, playerName == null
                    ? "&7Nessuna attivita registrata."
                    : "&7Nessuna attivita per &f" + playerName + "&7.");
            return;
        }

        C.send(sender, "&d&lATTIVITA" + (playerName == null ? "" : " &7di &f" + playerName));
        for (ActivityLog.Entry entry : entries) {
            C.send(sender, " &8" + TIME.format(Instant.ofEpochMilli(entry.time()))
                    + " &d" + entry.type()
                    + (entry.player() == null || entry.player().isEmpty() ? "" : " &f" + entry.player())
                    + " &7" + shorten(entry.detail(), 48));
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    @Subcommand("chatlock")
    @Description("Blocca o sblocca la chat")
    public void onChatLock(CommandSender sender) {
        ChatFilterModule filter = plugin.getManagers().modules()
                .module("chat-filter", ChatFilterModule.class);
        if (filter == null) {
            C.send(sender, "&cIl modulo chat-filter non e attivo.");
            return;
        }
        filter.setLocked(!filter.isLocked());
        plugin.getManagers().messages()
                .broadcast(filter.isLocked() ? "chat-lock-on" : "chat-lock-off", null);
    }

    @CatchUnknown
    public void onUnknown(CommandSender sender) {
        onDefault(sender);
    }
}
