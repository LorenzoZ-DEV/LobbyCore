package dev.lorenzzz.lobbycore.manager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * HikariCP pool for the lobby's player store, configured from the {@code database:} block of
 * config.yml. SQLite is the zero-config default and is right for a single lobby; a network
 * running several lobbies wants MySQL so a player's hider preference and stats follow them
 * between them.
 *
 * <p>Both JDBC drivers are bundled, so neither backend depends on what the server happens to
 * have on its classpath.
 */
public final class StorageManager implements Manager {

    private final LobbyCore plugin;
    private HikariDataSource dataSource;
    private String prefix = "lobby_";
    private boolean sqlite = true;

    public StorageManager(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "storage"; }

    @Override
    public void enable() {
        YamlConfiguration config = plugin.getManagers().config().raw();
        String type = config.getString("database.type", "sqlite").toLowerCase();
        prefix = config.getString("database.prefix", "lobby_");
        sqlite = !type.equals("mysql") && !type.equals("mariadb");

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("lobbycore-pool");
        hikari.setMaximumPoolSize(config.getInt("database.pool.maximum-pool-size", 10));
        hikari.setMinimumIdle(config.getInt("database.pool.minimum-idle", 2));
        hikari.setConnectionTimeout(config.getLong("database.pool.connection-timeout", 30_000L));
        hikari.setIdleTimeout(config.getLong("database.pool.idle-timeout", 600_000L));
        hikari.setMaxLifetime(config.getLong("database.pool.max-lifetime", 1_800_000L));
        hikari.setLeakDetectionThreshold(config.getLong("database.pool.leak-detection-threshold", 0L));

        switch (type) {
            case "mysql" -> configureRemote(hikari, config, "com.mysql.cj.jdbc.Driver", "mysql", 3306);
            case "mariadb" -> configureRemote(hikari, config, "org.mariadb.jdbc.Driver", "mariadb", 3306);
            default -> configureSqlite(hikari, config);
        }

        dataSource = new HikariDataSource(hikari);
        createTables();
        C.info("Storage pronto (&f" + type + "&7).");
    }

    private void configureRemote(HikariConfig hikari, YamlConfiguration config,
                                 String driver, String scheme, int defaultPort) {
        String host = config.getString("database.host", "localhost");
        int port = config.getInt("database.port", defaultPort);
        String database = config.getString("database.name", "lobbycore");
        String params = config.getString("database.params", "useSSL=false&allowPublicKeyRetrieval=true");
        hikari.setDriverClassName(driver);
        hikari.setJdbcUrl("jdbc:" + scheme + "://" + host + ":" + port + "/" + database
                + (params == null || params.isEmpty() ? "" : "?" + params));
        hikari.setUsername(config.getString("database.username", "root"));
        hikari.setPassword(config.getString("database.password", ""));
    }

    private void configureSqlite(HikariConfig hikari, YamlConfiguration config) {
        File file = new File(plugin.getDataFolder(), config.getString("database.name", "lobbycore") + ".db");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        // SQLite is a file, not a server: there is nothing to keep alive and nothing to reconnect
        // to, and a pool that churns connections just reopens the same file over and over.
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(1);
        hikari.setMaxLifetime(0);
        hikari.setIdleTimeout(0);
    }

    private void createTables() {
        // AUTO_INCREMENT-free on purpose: the UUID is the key on both backends, so the same DDL
        // works for SQLite and MySQL with only the text type differing.
        String varchar = sqlite ? "TEXT" : "VARCHAR(36)";
        String name = sqlite ? "TEXT" : "VARCHAR(16)";
        execute("CREATE TABLE IF NOT EXISTS " + prefix + "players ("
                + "uuid " + varchar + " NOT NULL PRIMARY KEY,"
                + "name " + name + " NOT NULL,"
                + "first_join BIGINT NOT NULL DEFAULT 0,"
                + "last_join BIGINT NOT NULL DEFAULT 0,"
                + "playtime BIGINT NOT NULL DEFAULT 0,"
                + "players_hidden BOOLEAN NOT NULL DEFAULT FALSE,"
                + "pvp_kills INT NOT NULL DEFAULT 0,"
                + "pvp_deaths INT NOT NULL DEFAULT 0"
                + ")");
    }

    /**
     * A pooled connection.
     *
     * <p>The explicit check earns its two lines: when {@link #enable()} throws — a bad host or a
     * missing driver is the usual reason — {@code dataSource} stays null and every caller gets a
     * bare NullPointerException, which modules then log as "(null)". Callers already catch
     * SQLException, so they log the actual cause instead.
     */
    public Connection connection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("il pool non e disponibile — controlla la riga "
                    + "'Failed enabling storage' all'avvio (database.type in config.yml)");
        }
        return dataSource.getConnection();
    }

    public boolean available() {
        return dataSource != null && !dataSource.isClosed();
    }

    public String prefix() {
        return prefix;
    }

    /** Which upsert dialect callers have to build — the two families do not share one. */
    public boolean isSqlite() {
        return sqlite;
    }

    public String table(String name) {
        return prefix + name;
    }

    public void execute(String sql) {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            C.warning("Errore SQL: " + e.getMessage());
        }
    }

    @Override
    public void disable() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        dataSource = null;
    }
}
