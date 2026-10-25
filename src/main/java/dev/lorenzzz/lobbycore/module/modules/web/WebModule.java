package dev.lorenzzz.lobbycore.module.modules.web;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * The admin panel: a small HTTP server that shows what the lobby is doing and lets an operator
 * change it.
 *
 * <p>It is a module like any other, which means it can be switched off — and unlike the rest, it
 * is the one module the panel refuses to switch off <em>from inside the panel</em>, because doing
 * so would tear down the connection carrying the request.
 *
 * <p>Nothing here holds state the rest of the plugin depends on. The activity feed and the error
 * store are managers, so they keep recording with the panel stopped; this module only serves a
 * view of them.
 */
public final class WebModule extends AbstractModule {

    /** Referenced by the reload paths that must leave this module alone. */
    public static final String ID = "web";

    private WebServer server;
    private WebAuth auth;
    private String boundHost;
    private int boundPort = -1;

    public WebModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return ID; }
    @Override public String configFile() { return "web"; }

    @Override
    public String description() {
        return "Pannello web: stato, log dei giocatori, errori e config";
    }

    @Override
    public void onEnable() {
        WebSettings settings = new WebSettings(config());

        int port = findAvailablePort(settings.port(), settings.port() + settings.portScanRange());
        if (port == -1) {
            C.warning("Pannello: nessuna porta libera fra " + settings.port()
                    + " e " + (settings.port() + settings.portScanRange()) + " — non parte.");
            return;
        }
        if (port != settings.port()) {
            C.info("Pannello: la porta &f" + settings.port()
                    + "&7 era occupata, uso la &f" + port + "&7.");
        }

        auth = new WebAuth(settings.token(), settings.tempTokenTtlMillis(),
                settings.sessionTtlMillis(), settings.secureCookie());

        server = new WebServer(plugin, auth,
                new WebStats(plugin, System.currentTimeMillis()),
                new ConfigFiles(plugin), settings);

        try {
            server.start(settings.host(), port);
            boundHost = settings.host();
            boundPort = port;
            C.info("Pannello attivo su &f" + displayHost() + ":" + port);
            if (settings.allowConsole()) {
                C.warning("Pannello: la console remota e ATTIVA (web.security.allow_console). "
                        + "Chi ha il token puo eseguire qualsiasi comando.");
            }
        } catch (Exception e) {
            C.warning("Pannello non avviato: " + e.getMessage());
            server = null;
            auth = null;
            boundPort = -1;
        }
    }

    @Override
    protected void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        auth = null;
        boundHost = null;
        boundPort = -1;
    }

    public boolean isServing() {
        return server != null;
    }

    public String boundHost() {
        return boundHost;
    }

    public int boundPort() {
        return boundPort;
    }

    /** A URL worth showing a staffer in chat. */
    public String url() {
        return "http://" + displayHost() + ":" + boundPort;
    }

    /** A single-use token for {@code /lobbycore panel}; null when the panel is not serving. */
    public String generateTempToken() {
        return auth == null ? null : auth.generateTempToken();
    }

    public long tempTokenMinutes() {
        return auth == null ? 0 : auth.tempTokenTtlMillis() / 60_000L;
    }

    /** 0.0.0.0 is a bind address, not somewhere a browser can go. */
    private String displayHost() {
        return boundHost == null || boundHost.isBlank() || boundHost.equals("0.0.0.0")
                ? "localhost" : boundHost;
    }

    /**
     * The first free port in the range.
     *
     * <p>Scanning rather than failing outright matters on a box running several lobbies from the
     * same config: they would all ask for 8085, and only the first would come up. The console
     * says which port was actually taken.
     */
    private static int findAvailablePort(int start, int end) {
        for (int port = start; port <= end; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return socket.getLocalPort();
            } catch (IOException ignored) {
                // In use — try the next one.
            }
        }
        return -1;
    }
}
