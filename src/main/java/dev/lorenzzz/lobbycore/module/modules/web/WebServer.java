package dev.lorenzzz.lobbycore.module.modules.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.manager.ActivityLog;
import dev.lorenzzz.lobbycore.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The panel's HTTP layer.
 *
 * <p>Every route sits behind {@link WebAuth}: a request either already carries a session cookie or
 * presents a token, which is swapped for one. Anything else gets a 401 and, for a browser, a small
 * page asking for the token — this thing writes config files and stops modules, so it must never
 * answer an anonymous caller.
 *
 * <p>Mutations are POST-only and the session cookie is {@code SameSite=Strict}, which is what keeps
 * another site's page from making the browser act on the admin's behalf.
 */
final class WebServer {

    private static final String ASSET_PREFIX = "web/";

    /**
     * A wedged client costs one parked thread until its socket finally errors out, and keeps its
     * slot for exactly that long — so this is a ceiling on threads, not on tabs. Legitimate use
     * needs one per open panel.
     */
    private static final int MAX_STREAMS = 16;

    /** Nothing this panel accepts is bigger than a config file. */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Map<String, String> ASSETS = Map.of(
            "index.html", "text/html; charset=utf-8",
            "style.css", "text/css; charset=utf-8",
            "app.js", "application/javascript; charset=utf-8");

    /** Image types the branding route will serve, and nothing else. */
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "svg", "image/svg+xml",
            "gif", "image/gif",
            "ico", "image/x-icon");

    private final LobbyCore plugin;
    private final WebAuth auth;
    private final WebStats stats;
    private final ConfigFiles files;
    private final WebSettings settings;

    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService broadcaster;

    private volatile long lastActivityVersion = -1L;
    private volatile long lastErrorVersion = -1L;

    private String boundHost;
    private int boundPort;

    WebServer(LobbyCore plugin, WebAuth auth, WebStats stats, ConfigFiles files, WebSettings settings) {
        this.plugin = plugin;
        this.auth = auth;
        this.stats = stats;
        this.files = files;
        this.settings = settings;
    }

    void start(String host, int port) throws IOException {
        this.boundHost = host;
        this.boundPort = port;

        executor = Executors.newFixedThreadPool(settings.workerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "lobbycore-web");
            thread.setDaemon(true);
            return thread;
        });

        // One thread pushes snapshots to every stream. scheduleAtFixedRate never overlaps runs,
        // and the tick only builds strings and hands them to per-client queues — so a slow browser
        // cannot make the server grow threads.
        broadcaster = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lobbycore-sse");
            thread.setDaemon(true);
            return thread;
        });
        broadcaster.scheduleAtFixedRate(this::broadcast,
                settings.streamPeriodMillis(), settings.streamPeriodMillis(), TimeUnit.MILLISECONDS);

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
    }

    void stop() {
        if (broadcaster != null) {
            broadcaster.shutdownNow();
            broadcaster = null;
        }
        for (SseClient client : clients) client.close();
        clients.clear();

        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // --------------------------------------------------------------- dispatch

    private void dispatch(HttpExchange exchange) throws IOException {
        // The event stream stays open past this method; everything else is closed here.
        boolean streaming = false;
        try {
            securityHeaders(exchange);
            String path = exchange.getRequestURI().getPath();

            if (!settings.allowsAddress(remoteHost(exchange))) {
                sendJson(exchange, 403, error("indirizzo non consentito"));
                return;
            }

            /*
             * Branding images are served before the auth check, deliberately. The login page
             * uses them, and a login page whose own background returns 401 is a broken login
             * page. They carry no information — a logo and a lobby screenshot — so the only
             * thing an unauthenticated caller learns is which server this is, which they already
             * knew from connecting to it.
             */
            if (path.startsWith("/assets/")) {
                serveImage(exchange, path.substring("/assets/".length()));
                return;
            }

            if (path.equals("/api/logout")) {
                auth.logout(exchange);
                exchange.getResponseHeaders().add("Set-Cookie", auth.clearedCookie());
                sendJson(exchange, 200, "{\"ok\":true}");
                return;
            }

            if (!authorize(exchange)) {
                rejectUnauthorized(exchange, path);
                return;
            }

            streaming = route(exchange, path);
        } catch (Exception e) {
            try {
                sendJson(exchange, 500, error(String.valueOf(e.getMessage())));
            } catch (IOException ignored) {
                // The client already hung up.
            }
        } finally {
            if (!streaming) exchange.close();
        }
    }

    /** Returns true when the response is a stream this method must leave open. */
    private boolean route(HttpExchange exchange, String path) throws IOException {
        String asset = assetFor(path);
        if (asset != null) {
            serveResource(exchange, asset);
            return false;
        }

        switch (path) {
            case "/api/ui" -> sendJson(exchange, 200, settings.uiJson());
            case "/api/stats" -> sendJson(exchange, 200, stats.snapshot());
            case "/api/players" -> sendJson(exchange, 200, stats.playersJson());
            case "/api/history" -> sendJson(exchange, 200, stats.historyJson());
            case "/api/activity" -> sendJson(exchange, 200, activityJson());
            case "/api/errors" -> sendJson(exchange, 200, plugin.getManagers().errors().json());
            case "/api/files" -> sendJson(exchange, 200, filesJson());
            case "/api/file" -> fileRoute(exchange);
            case "/api/file/restore" -> restoreRoute(exchange);
            case "/api/activity/clear" -> clearActivityRoute(exchange);
            case "/api/errors/clear" -> clearErrorsRoute(exchange);
            case "/api/module/toggle" -> moduleToggleRoute(exchange);
            case "/api/reload" -> reloadRoute(exchange);
            case "/api/broadcast" -> broadcastRoute(exchange);
            case "/api/player/kick" -> kickRoute(exchange);
            case "/api/player/message" -> messageRoute(exchange);
            case "/api/console" -> consoleRoute(exchange);
            case "/api/stream" -> {
                return sse(exchange);
            }
            default -> sendText(exchange, 404, "not found");
        }
        return false;
    }

    private static String assetFor(String path) {
        if (path.equals("/")) return "index.html";
        if (path.startsWith("/api/")) return null;
        String name = path.substring(path.lastIndexOf('/') + 1);
        return ASSETS.containsKey(name) ? name : null;
    }

    // ------------------------------------------------------------------- auth

    private boolean authorize(HttpExchange exchange) {
        if (auth.hasSession(exchange)) return true;

        String session = auth.login(exchange);
        if (session == null) return false;

        exchange.getResponseHeaders().add("Set-Cookie", auth.sessionCookie(session));
        plugin.getManagers().activity().record(ActivityLog.PANEL, (String) null,
                "login dal pannello da " + remoteHost(exchange));
        return true;
    }

    /** API callers get JSON they can act on; browsers get a page that asks for the token. */
    private void rejectUnauthorized(HttpExchange exchange, String path) throws IOException {
        if (path.startsWith("/api/")) {
            sendJson(exchange, 401, error("non autorizzato"));
        } else {
            sendHtml(exchange, 401, LoginPage.html(settings));
        }
    }

    private static void securityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        // Everything the panel needs is served from here; nothing external is ever fetched.
        headers.set("Content-Security-Policy",
                "default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self'; "
                        + "img-src 'self' data:; connect-src 'self'; form-action 'self'");
    }

    private static String remoteHost(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null ? "unknown" : remote.getHostString();
    }

    // ----------------------------------------------------------------- routes

    private void fileRoute(HttpExchange exchange) throws IOException {
        String relative = WebAuth.queryParam(exchange, "path");
        if (relative == null) {
            sendJson(exchange, 400, error("manca il parametro path"));
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String content = files.read(relative);
            if (content == null) {
                sendJson(exchange, 404, error("file non trovato: " + relative));
                return;
            }
            sendJson(exchange, 200, Json.object(json -> json
                    .str("path", relative)
                    .bool("readonly", files.isProtected(relative))
                    .str("content", content)));
            return;
        }

        if (!requirePost(exchange)) return;

        String body = readBody(exchange);
        if (body == null) {
            sendJson(exchange, 413, error("file troppo grande"));
            return;
        }

        String failure = files.write(relative, body, remoteHost(exchange));
        if (failure != null) {
            sendJson(exchange, 400, error(failure));
            return;
        }
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private void restoreRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        String relative = WebAuth.queryParam(exchange, "path");
        String failure = files.restore(relative, remoteHost(exchange));
        if (failure != null) {
            sendJson(exchange, 400, error(failure));
            return;
        }
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private void clearActivityRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        plugin.getManagers().activity().clear();
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private void clearErrorsRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        plugin.getManagers().errors().clear();
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    /**
     * Starts or stops one module and flips its flag in config.yml.
     *
     * <p>Runs on the server thread and waits: enabling a module registers listeners and schedules
     * tasks. Handing it to the scheduler and returning would send the HTTP response before
     * anything had changed, and the browser would redraw the state from before the click.
     */
    private void moduleToggleRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;

        String id = WebAuth.queryParam(exchange, "id");
        if (id == null || id.isBlank()) {
            sendJson(exchange, 400, error("manca il parametro id"));
            return;
        }
        // Switching the panel off from inside the panel would kill the connection mid-request.
        if (WebModule.ID.equals(id)) {
            sendJson(exchange, 400, error("il modulo web non si spegne dal pannello"));
            return;
        }

        String result = onMainThread(() -> {
            var managers = plugin.getManagers();
            if (managers.modules().module(id) == null) return "modulo sconosciuto: " + id;

            // Re-read first: the editor writes config.yml straight to disk, so the copy behind
            // getConfig() can be stale and saveConfig() would write those stale values back.
            plugin.reloadConfig();
            String path = "modules." + id;
            boolean newState = !plugin.getConfig().getBoolean(path, false);

            plugin.getConfig().set(path, newState);
            plugin.saveConfig();
            managers.config().reload();

            String failure = managers.modules().setEnabled(id, newState);
            managers.activity().record(ActivityLog.PANEL, (String) null,
                    "modulo " + id + " " + (newState ? "acceso" : "spento")
                            + " da " + remoteHost(exchange));
            return failure;
        });

        var modules = plugin.getManagers().modules();
        sendJson(exchange, result == null ? 200 : 400, Json.object(json -> json
                .bool("ok", result == null)
                .str("error", result == null ? "" : result)
                .str("id", id)
                .bool("enabled", plugin.getManagers().config().isEnabled(id))
                .bool("running", modules.isRunning(id))));
    }

    private void reloadRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;

        Integer running = onMainThread(() -> {
            var managers = plugin.getManagers();
            plugin.reloadConfig();
            managers.config().reload();
            managers.errors().reload();
            managers.activity().reload();
            managers.messages().reload();
            managers.menus().reload();
            // Never the panel itself: see ConfigFiles#applyReload.
            managers.modules().reload(Set.of(WebModule.ID));
            managers.activity().record(ActivityLog.PANEL, (String) null,
                    "reload dal pannello da " + remoteHost(exchange));
            return managers.modules().runningCount();
        });

        sendJson(exchange, 200, Json.object(json -> json
                .bool("ok", true)
                .num("modules", running == null ? 0 : running)));
    }

    private void broadcastRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        String text = readBody(exchange);
        if (text == null || text.isBlank()) {
            sendJson(exchange, 400, error("messaggio vuoto"));
            return;
        }

        onMainThread(() -> {
            var message = C.mm(text);
            for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(message);
            plugin.getManagers().activity().record(ActivityLog.PANEL, (String) null,
                    "broadcast da " + remoteHost(exchange));
            return true;
        });
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private void kickRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        String name = WebAuth.queryParam(exchange, "name");
        String reason = WebAuth.queryParam(exchange, "reason");

        Boolean done = onMainThread(() -> {
            Player target = Bukkit.getPlayerExact(name);
            if (target == null) return false;
            target.kick(C.mm(reason == null || reason.isBlank()
                    ? "&cSei stato espulso dallo staff." : reason));
            plugin.getManagers().activity().record(ActivityLog.PANEL, name,
                    "espulso dal pannello (" + remoteHost(exchange) + ")");
            return true;
        });

        if (Boolean.TRUE.equals(done)) {
            sendJson(exchange, 200, "{\"ok\":true}");
        } else {
            sendJson(exchange, 404, error("giocatore non online"));
        }
    }

    private void messageRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        String name = WebAuth.queryParam(exchange, "name");
        String text = readBody(exchange);

        Boolean done = onMainThread(() -> {
            Player target = Bukkit.getPlayerExact(name);
            if (target == null || text == null || text.isBlank()) return false;
            target.sendMessage(C.mm(text));
            plugin.getManagers().activity().record(ActivityLog.PANEL, name,
                    "messaggio dal pannello");
            return true;
        });

        if (Boolean.TRUE.equals(done)) {
            sendJson(exchange, 200, "{\"ok\":true}");
        } else {
            sendJson(exchange, 404, error("giocatore non online, o messaggio vuoto"));
        }
    }

    /**
     * Runs a console command.
     *
     * <p>Off unless {@code security.allow_console} says otherwise, and deliberately so: this is
     * full operator access over HTTP, and it should be a decision somebody made rather than
     * something that came switched on.
     */
    private void consoleRoute(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        if (!settings.allowConsole()) {
            sendJson(exchange, 403, error("console disattivata (security.allow_console in web.yml)"));
            return;
        }

        String command = readBody(exchange);
        if (command == null || command.isBlank()) {
            sendJson(exchange, 400, error("comando vuoto"));
            return;
        }

        String trimmed = command.trim();
        Boolean done = onMainThread(() -> {
            plugin.getManagers().activity().record(ActivityLog.PANEL, (String) null,
                    "console: /" + trimmed + " (" + remoteHost(exchange) + ")");
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), trimmed);
        });

        sendJson(exchange, 200, Json.object(json -> json
                .bool("ok", true)
                .bool("handled", Boolean.TRUE.equals(done))));
    }

    // -------------------------------------------------------------------- sse

    private boolean sse(HttpExchange exchange) throws IOException {
        if (clients.size() >= MAX_STREAMS) {
            // Nothing has been written yet, so this is still an ordinary response. Refusing is
            // the right failure: the alternative is growing threads again.
            C.warning("Pannello: rifiutato un nuovo stream, " + MAX_STREAMS + " gia aperti.");
            sendJson(exchange, 503, error("troppi stream aperti"));
            return false;
        }

        // Seed frames so the tab draws immediately instead of waiting for the next tick. Built
        // before the response starts, so a failure here is still an ordinary 500 and never leaves
        // a half-owned exchange behind.
        byte[] snapshot = frame("data: " + stats.snapshot() + "\n\n");
        byte[] players = frame("event: players\ndata: " + stats.playersJson() + "\n\n");
        byte[] activity = frame("event: activity\ndata: " + activityJson() + "\n\n");
        byte[] errors = frame("event: errors\ndata: " + plugin.getManagers().errors().json() + "\n\n");
        byte[] history = frame("event: history\ndata: " + stats.historyJson() + "\n\n");

        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        SseClient client = new SseClient(exchange, clients::remove);
        clients.add(client);
        client.start();
        client.send(snapshot);
        client.send(players);
        client.send(activity);
        client.send(errors);
        client.send(history);
        return true;
    }

    /**
     * Builds this tick's frames once and offers them to every stream. Runs on the broadcaster
     * thread and must never block: {@link SseClient#send} only queues, and drops clients that have
     * stopped draining. The snapshot doubles as the heartbeat that reveals dead connections.
     */
    private void broadcast() {
        // Clients hang around after being given up on, until their writer thread really ends, so
        // "not empty" is not the same as "somebody is listening".
        if (clients.stream().allMatch(SseClient::isClosed)) return;
        try {
            stats.sample();

            byte[] snapshot = frame("data: " + stats.snapshot() + "\n\n");
            byte[] players = frame("event: players\ndata: " + stats.playersJson() + "\n\n");

            // The feed and the error list only move when something happens. Pushing them every
            // tick was the bulk of the bytes and none of the information.
            byte[] activityFrame = null;
            long activityVersion = plugin.getManagers().activity().version();
            if (activityVersion != lastActivityVersion) {
                lastActivityVersion = activityVersion;
                activityFrame = frame("event: activity\ndata: " + activityJson() + "\n\n");
            }

            byte[] errorFrame = null;
            long errorVersion = plugin.getManagers().errors().version();
            if (errorVersion != lastErrorVersion) {
                lastErrorVersion = errorVersion;
                errorFrame = frame("event: errors\ndata: "
                        + plugin.getManagers().errors().json() + "\n\n");
            }

            for (SseClient client : clients) {
                client.send(snapshot);
                client.send(players);
                if (activityFrame != null) client.send(activityFrame);
                if (errorFrame != null) client.send(errorFrame);
            }
        } catch (Exception e) {
            // scheduleAtFixedRate drops the task on the first throw, and the panel would go quiet
            // with no explanation. Log it and keep ticking.
            C.warning("Pannello: broadcast fallito: " + e);
        }
    }

    private static byte[] frame(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------- plumbing

    private String activityJson() {
        return plugin.getManagers().activity().json(settings.feedLimit());
    }

    private String filesJson() {
        List<ConfigFiles.Entry> entries = files.list();
        return Json.array(entries, (writer, entry) -> writer.obj(json -> json
                .str("path", entry.path())
                .num("size", entry.size())
                .num("modified", entry.modified())
                .bool("readonly", files.isProtected(entry.path()))));
    }

    private static String error(String message) {
        return Json.object(json -> json.str("error", message));
    }

    private boolean requirePost(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) return true;
        sendText(exchange, 405, "method not allowed");
        return false;
    }

    /** The request body as UTF-8, or {@code null} when it is over the cap. */
    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Runs {@code work} on the server thread and waits for its result. */
    private <T> T onMainThread(Callable<T> work) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return work.call();
            } catch (Exception e) {
                return null;
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(work.call());
            } catch (Exception e) {
                future.complete(null);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Serves one branding image out of {@code web/assets/} in the jar.
     *
     * <p>The name is checked rather than trusted: no slashes, no dots beyond the extension, and
     * the extension has to be one of {@link #IMAGE_TYPES}. {@code getResource} would happily
     * resolve {@code ../config.yml} otherwise, and the panel would hand out the plugin's config
     * to anyone who can reach the port — before authentication, since this route runs first.
     */
    private void serveImage(HttpExchange exchange, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : IMAGE_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));

        if (type == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            sendText(exchange, 404, "not found");
            return;
        }

        try (InputStream in = plugin.getResource(ASSET_PREFIX + "assets/" + name)) {
            if (in == null) {
                sendText(exchange, 404, "not found");
                return;
            }
            // Overrides the blanket no-store: these never change without a new jar, and the
            // lobby screenshot is megabytes that should not come down on every navigation.
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            send(exchange, 200, type, in.readAllBytes());
        }
    }

    private void serveResource(HttpExchange exchange, String asset) throws IOException {
        try (InputStream in = plugin.getResource(ASSET_PREFIX + asset)) {
            if (in == null) {
                sendText(exchange, 404, "asset mancante: " + asset);
                return;
            }
            send(exchange, 200, ASSETS.get(asset), in.readAllBytes());
        }
    }

    private void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendHtml(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int code, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    String boundHost() { return boundHost; }

    int boundPort() { return boundPort; }
}
