package dev.lorenzzz.lobbycore.module.modules.web;

import com.sun.net.httpserver.HttpExchange;
import dev.lorenzzz.lobbycore.C;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access control for the panel.
 *
 * <p>Two ways in: the long-lived token from {@code web.yml} (or a random one printed to console
 * at boot when none is set), and the short-lived single-use tokens handed out in game by
 * {@code /lobbycore panel}. Either is exchanged <em>once</em> for a session cookie, and every
 * later request rides on that — including the page assets and the event stream, neither of which
 * can carry a custom header. Without the exchange a browser could only authenticate its very
 * first request.
 *
 * <p>The panel writes config files and toggles modules, so there is no anonymous mode and no
 * "read-only without a token": an unauthenticated caller gets a 401 and nothing else.
 */
public final class WebAuth {

    public static final String COOKIE_NAME = "lobbycore_session";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String mainToken;
    private final long tempTokenTtlMillis;
    private final long sessionTtlMillis;
    private final boolean secureCookie;

    private final Map<String, Long> tempTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public WebAuth(String configuredToken, long tempTokenTtlMillis,
                   long sessionTtlMillis, boolean secureCookie) {
        this.tempTokenTtlMillis = tempTokenTtlMillis;
        this.sessionTtlMillis = sessionTtlMillis;
        this.secureCookie = secureCookie;

        boolean generated = configuredToken == null || configuredToken.isBlank();
        this.mainToken = generated ? randomId(32) : configuredToken.trim();
        if (generated) {
            C.info("&7Token del pannello: &f" + mainToken);
            C.info("&7Mettilo in &fmodules/web.yml &8(&7auth.token&8) &7per non perderlo al riavvio.");
        }
    }

    public String token() {
        return mainToken;
    }

    /** A single-use token for {@code /lobbycore panel}. */
    public String generateTempToken() {
        purgeExpired();
        String token = randomId(12);
        tempTokens.put(token, System.currentTimeMillis() + tempTokenTtlMillis);
        return token;
    }

    public long tempTokenTtlMillis() {
        return tempTokenTtlMillis;
    }

    // ------------------------------------------------------------------- checks

    /** True when the request carries a session cookie we issued and that has not expired. */
    public boolean hasSession(HttpExchange exchange) {
        String id = cookie(exchange, COOKIE_NAME);
        if (id == null) return false;

        Long expiry = sessions.get(id);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            sessions.remove(id);
            return false;
        }
        // Sliding window: an admin with the tab open stays logged in.
        sessions.put(id, System.currentTimeMillis() + sessionTtlMillis);
        return true;
    }

    /**
     * Validates a token from {@code ?token=} or the {@code X-Token} header and, on success,
     * returns a fresh session id. {@code null} means the caller is not authorised.
     */
    public String login(HttpExchange exchange) {
        purgeExpired();

        String candidate = queryParam(exchange, "token");
        if (candidate == null) candidate = exchange.getRequestHeaders().getFirst("X-Token");
        if (candidate == null || candidate.isEmpty()) return null;

        if (!constantTimeEquals(mainToken, candidate) && !consumeTempToken(candidate)) return null;

        String session = randomId(32);
        sessions.put(session, System.currentTimeMillis() + sessionTtlMillis);
        return session;
    }

    public void logout(HttpExchange exchange) {
        String id = cookie(exchange, COOKIE_NAME);
        if (id != null) sessions.remove(id);
    }

    public int activeSessions() {
        purgeExpired();
        return sessions.size();
    }

    public String sessionCookie(String session) {
        // SameSite=Strict, because every route here is state-changing and none of them should
        // ever be reachable from another site's page.
        return COOKIE_NAME + "=" + session + "; Path=/; HttpOnly; SameSite=Strict"
                + (secureCookie ? "; Secure" : "")
                + "; Max-Age=" + (sessionTtlMillis / 1000L);
    }

    /** An already-expired cookie of the same name, so the browser drops it on logout. */
    public String clearedCookie() {
        return COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict"
                + (secureCookie ? "; Secure" : "") + "; Max-Age=0";
    }

    private boolean consumeTempToken(String token) {
        // Removed whether or not it was still valid: single use means single use.
        Long expiry = tempTokens.remove(token);
        return expiry != null && System.currentTimeMillis() <= expiry;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        tempTokens.values().removeIf(expiry -> now > expiry);
        sessions.values().removeIf(expiry -> now > expiry);
    }

    // ------------------------------------------------------------------ parsing

    static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals < 0 || !name.equals(part.substring(0, equals))) continue;
            return URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String cookie(HttpExchange exchange, String name) {
        var headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String entry = part.trim();
                int equals = entry.indexOf('=');
                if (equals < 0 || !name.equals(entry.substring(0, equals))) continue;
                return entry.substring(equals + 1);
            }
        }
        return null;
    }

    /** Length-independent comparison, so a wrong token leaks nothing through timing. */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomId(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
