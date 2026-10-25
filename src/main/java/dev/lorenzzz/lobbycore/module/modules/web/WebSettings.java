package dev.lorenzzz.lobbycore.module.modules.web;

import dev.lorenzzz.lobbycore.util.Json;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything about the panel that an admin can change, read once into fields.
 *
 * <p>This exists because "make it configurable" has to mean more than the port. Which tabs are
 * visible, how often the stream ticks, how long a session lasts, which addresses may connect,
 * whether the console box exists at all — all of it comes from {@code modules/web.yml}, and the
 * front end is told about the parts that affect what it draws.
 */
final class WebSettings {

    /** Tabs the front end knows how to render. An unknown name in the config is ignored. */
    private static final List<String> KNOWN_TABS =
            List.of("overview", "players", "activity", "errors", "modules", "config", "tools");

    private final String host;
    private final int port;
    private final int portScanRange;

    private final String token;
    private final long sessionTtlMillis;
    private final long tempTokenTtlMillis;
    private final boolean secureCookie;

    private final int workerThreads;
    private final long streamPeriodMillis;
    private final int feedLimit;

    private final Set<String> allowedAddresses;
    private final boolean allowConsole;

    private final String uiTitle;
    private final String uiAccent;
    private final String uiTheme;
    private final Set<String> tabs;

    private final String brandLogo;
    private final String brandMark;
    private final String brandBackground;
    private final double brandBackgroundOpacity;
    private final String brandTexture;

    WebSettings(YamlConfiguration config) {
        host = config.getString("web.host", "0.0.0.0");
        port = config.getInt("web.port", 8085);
        portScanRange = Math.max(0, config.getInt("web.port_scan_range", 20));

        token = config.getString("web.auth.token", "");
        sessionTtlMillis = Math.max(60L, config.getLong("web.auth.session_minutes", 360L)) * 60_000L;
        tempTokenTtlMillis = Math.max(1L, config.getLong("web.auth.ingame_token_minutes", 5L)) * 60_000L;
        // Only meaningful behind a TLS proxy; a Secure cookie over plain http is never sent back
        // and the panel would appear to reject every login.
        secureCookie = config.getBoolean("web.auth.secure_cookie", false);

        workerThreads = Math.max(2, Math.min(16, config.getInt("web.worker_threads", 4)));
        streamPeriodMillis = Math.max(250L, config.getLong("web.stream_interval_ms", 1000L));
        feedLimit = Math.max(20, Math.min(2000, config.getInt("web.feed_limit", 200)));

        allowedAddresses = new LinkedHashSet<>();
        for (String address : config.getStringList("web.security.allowed_addresses")) {
            if (address != null && !address.isBlank()) allowedAddresses.add(address.trim());
        }
        allowConsole = config.getBoolean("web.security.allow_console", false);

        uiTitle = config.getString("web.ui.title", "LobbyCore");
        uiAccent = config.getString("web.ui.accent", "#9863E7");
        uiTheme = config.getString("web.ui.theme", "dark").toLowerCase(Locale.ROOT);

        tabs = new LinkedHashSet<>();
        List<String> configured = config.getStringList("web.ui.tabs");
        for (String tab : configured.isEmpty() ? KNOWN_TABS : configured) {
            String name = tab.toLowerCase(Locale.ROOT).trim();
            if (KNOWN_TABS.contains(name)) tabs.add(name);
        }
        // An empty or entirely bogus list would give a panel with no tabs at all, which reads as
        // a broken build rather than a configuration mistake.
        if (tabs.isEmpty()) tabs.addAll(KNOWN_TABS);

        // Branding. Every one of these may be blank, and blank means "draw the plain version"
        // rather than "draw a broken image" — a server that drops its own art in should not have
        // to also delete ours, and a server that wants none should not have to supply any.
        brandLogo = asset(config.getString("web.ui.branding.logo", "assets/logo-server.png"));
        brandMark = asset(config.getString("web.ui.branding.mark", "assets/logo.png"));
        brandBackground = asset(config.getString("web.ui.branding.background", "assets/background.png"));
        brandBackgroundOpacity = clamp(config.getDouble("web.ui.branding.background_opacity", 0.10));
        brandTexture = asset(config.getString("web.ui.branding.texture", "assets/navbar.png"));
    }

    /**
     * Normalises a branding path to something the panel can request.
     *
     * <p>Only the {@code assets/} folder inside the jar is reachable, so a value is either blank,
     * an absolute URL the admin hosts elsewhere, or a file name we turn into {@code /assets/x}.
     */
    private static String asset(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
        if (trimmed.startsWith("/")) return trimmed;
        if (trimmed.startsWith("assets/")) return "/" + trimmed;
        return "/assets/" + trimmed;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    String host() { return host; }
    int port() { return port; }
    int portScanRange() { return portScanRange; }

    String token() { return token; }
    long sessionTtlMillis() { return sessionTtlMillis; }
    long tempTokenTtlMillis() { return tempTokenTtlMillis; }
    boolean secureCookie() { return secureCookie; }

    int workerThreads() { return workerThreads; }
    long streamPeriodMillis() { return streamPeriodMillis; }
    int feedLimit() { return feedLimit; }

    boolean allowConsole() { return allowConsole; }

    /**
     * Whether a caller's address may connect at all.
     *
     * <p>An empty list means "anywhere", which is the only sane default for something bound to
     * 0.0.0.0 behind whatever firewall the host already has. Matching is by prefix so a whole
     * subnet can be written as {@code 10.0.} without pulling in a CIDR parser for a feature most
     * servers will set to one office IP or leave empty.
     */
    boolean allowsAddress(String address) {
        if (allowedAddresses.isEmpty()) return true;
        if (address == null) return false;
        for (String allowed : allowedAddresses) {
            if (address.equals(allowed) || address.startsWith(allowed)) return true;
        }
        return false;
    }

    String accent() { return uiAccent; }
    String logo() { return brandLogo; }
    String background() { return brandBackground; }
    double backgroundOpacity() { return brandBackgroundOpacity; }

    /** The parts the front end needs in order to draw itself. */
    String uiJson() {
        return Json.object(json -> json
                .str("title", uiTitle)
                .str("accent", uiAccent)
                .str("theme", uiTheme)
                .bool("console", allowConsole)
                .num("interval", streamPeriodMillis)
                .arr("tabs", tabs, (writer, tab) -> writer.str(tab))
                .obj("branding", b -> b
                        .str("logo", brandLogo)
                        .str("mark", brandMark)
                        .str("background", brandBackground)
                        .num("backgroundOpacity", brandBackgroundOpacity)
                        .str("texture", brandTexture)));
    }
}
