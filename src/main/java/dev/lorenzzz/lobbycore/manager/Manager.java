package dev.lorenzzz.lobbycore.manager;

/** A plugin-wide service with a start/stop pair, owned by {@link ManagerService}. */
public interface Manager {

    String name();

    void enable();

    void disable();

    /** Re-reads whatever backs this manager. Default is a no-op for stateless ones. */
    default void reload() {}
}
