package dev.lorenzzz.lobbycore.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import dev.lorenzzz.lobbycore.LobbyCore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * The gamemode shortcuts a lobby's staff use constantly. Each is its own permission so a builder
 * can get creative without getting spectator.
 */
public final class GamemodeCommands extends BaseCommand {

    private final LobbyCore plugin;

    public GamemodeCommands(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @CommandAlias("gms|gmsurvival")
    @CommandPermission("lobbycore.gamemode.survival")
    @CommandCompletion("@players")
    @Description("Survival")
    public void onSurvival(CommandSender sender, @Optional String target) {
        apply(sender, target, GameMode.SURVIVAL);
    }

    @CommandAlias("gmc|gmcreative")
    @CommandPermission("lobbycore.gamemode.creative")
    @CommandCompletion("@players")
    @Description("Creative")
    public void onCreative(CommandSender sender, @Optional String target) {
        apply(sender, target, GameMode.CREATIVE);
    }

    @CommandAlias("gma|gmadventure")
    @CommandPermission("lobbycore.gamemode.adventure")
    @CommandCompletion("@players")
    @Description("Adventure")
    public void onAdventure(CommandSender sender, @Optional String target) {
        apply(sender, target, GameMode.ADVENTURE);
    }

    @CommandAlias("gmsp|gmspectator")
    @CommandPermission("lobbycore.gamemode.spectator")
    @CommandCompletion("@players")
    @Description("Spectator")
    public void onSpectator(CommandSender sender, @Optional String target) {
        apply(sender, target, GameMode.SPECTATOR);
    }

    private void apply(CommandSender sender, String targetName, GameMode mode) {
        var messages = plugin.getManagers().messages();

        Player target;
        if (targetName == null) {
            if (!(sender instanceof Player self)) {
                messages.send(sender, "players-only");
                return;
            }
            target = self;
        } else {
            if (!sender.hasPermission("lobbycore.gamemode.others")) {
                messages.send(sender, "no-permission");
                return;
            }
            target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                messages.send(sender, "player-not-found", Map.of("%player%", targetName));
                return;
            }
        }

        target.setGameMode(mode);
        String readable = mode.name().toLowerCase();
        messages.send(target, "gamemode-changed", Map.of("%gamemode%", readable));
        if (target != sender) {
            messages.send(sender, "gamemode-changed-other", Map.of(
                    "%player%", target.getName(),
                    "%gamemode%", readable));
        }
    }
}
