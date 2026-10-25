package dev.lorenzzz.lobbycore.action;

import dev.lorenzzz.lobbycore.LobbyCore;
import org.bukkit.entity.Player;

/**
 * One {@code [NAME] argument} line from a config list. Implementations run on the main thread
 * and receive the argument already stripped of its {@code [NAME]} prefix and resolved through
 * PlaceholderAPI.
 */
@FunctionalInterface
public interface Action {

    void execute(LobbyCore plugin, Player player, String argument);
}
