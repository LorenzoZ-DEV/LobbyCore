package dev.lorenzzz.lobbycore.module;

import dev.lorenzzz.lobbycore.manager.ManagerService;

/**
 * One feature of the lobby, started only when {@code modules.<id>: true} in config.yml.
 *
 * <p>A module owns its listeners, tasks and commands and gives all of them back on
 * {@link #onDisable()} — that is what lets {@code /lobbycore module <id> off} work at runtime
 * without a server restart, and what keeps a reload from stacking duplicate handlers.
 */
public interface Module {

    /** Lower-case id, matching the key under {@code modules:} in config.yml. */
    String id();

    /** The file under {@code modules/} this module reads, without the extension. */
    String configFile();

    /** Injects the shared managers. Always called before {@link #onEnable()}. */
    void bind(ManagerService managers);

    void onEnable();

    void onDisable();

    /** A short line for {@code /lobbycore modules}. */
    default String description() {
        return "";
    }
}
