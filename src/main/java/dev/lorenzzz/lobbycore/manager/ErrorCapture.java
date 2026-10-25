package dev.lorenzzz.lobbycore.manager;

import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.util.Json;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Catches what goes wrong, so the panel can show it instead of an admin scrolling latest.log.
 *
 * <p>It attaches a {@link Handler} to the root logger, which is where every plugin's messages end
 * up — a lobby's problems are rarely only ours, and an admin looking at "errors" wants the stack
 * trace from whatever actually threw. Records below WARNING are ignored.
 *
 * <p>Identical failures are folded into one entry with a counter rather than appended. A single
 * broken listener throwing once per player movement produces thousands of records a minute; a
 * list of those is not a diagnostic, it is a denial of service against the person reading it.
 * The fold key is the level, the logger and the message with digits and hex blanked out, so
 * "Entity 1934 is invalid" and "Entity 2201 is invalid" are recognised as the same fault.
 *
 * <p>A manager rather than a part of the web module: errors are worth capturing whether or not
 * anybody has the dashboard open, and {@code /lobbycore errors} reads the same store.
 */
public final class ErrorCapture implements Manager {

    /** One distinct fault, with how often it has happened. */
    public record Issue(String key, String level, String source, String message,
                        String stack, long firstSeen, long lastSeen, int count) {

        Issue seenAgain(long when) {
            return new Issue(key, level, source, message, stack, firstSeen, when, count + 1);
        }
    }

    /** Longer than this and a stack trace is scrollback, not information. */
    private static final int MAX_STACK_LINES = 24;

    private final LobbyCore plugin;
    private final Map<String, Issue> issues = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();

    /**
     * Guards against a loop: anything logged from inside {@link #publish} would come straight
     * back through the same handler. Per-thread, because the handler is called from every thread
     * the server has.
     */
    private final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Handler handler;
    private Logger attachedTo;
    private int capacity = 100;
    private boolean enabled = true;
    private boolean captureWarnings = true;

    public ErrorCapture(LobbyCore plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "errors"; }

    @Override
    public void enable() {
        readConfig();
        if (!enabled) return;

        handler = new Handler() {
            @Override public void publish(LogRecord record) { capture(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        handler.setLevel(Level.WARNING);

        // The root logger, not the plugin's: a NullPointerException thrown by another plugin's
        // listener is exactly the thing an admin opens this panel to find.
        attachedTo = Logger.getLogger("");
        attachedTo.addHandler(handler);
    }

    @Override
    public void disable() {
        if (attachedTo != null && handler != null) {
            attachedTo.removeHandler(handler);
        }
        handler = null;
        attachedTo = null;
        issues.clear();
    }

    @Override
    public void reload() {
        readConfig();
        trim();
    }

    private void readConfig() {
        var config = plugin.getManagers().config().raw();
        enabled = config.getBoolean("errors.enabled", true);
        capacity = Math.max(20, Math.min(500, config.getInt("errors.capacity", 100)));
        captureWarnings = config.getBoolean("errors.capture_warnings", true);
    }

    // ------------------------------------------------------------------ capture

    private void capture(LogRecord record) {
        if (record == null) return;
        if (Boolean.TRUE.equals(reentrant.get())) return;

        Level level = record.getLevel();
        if (level.intValue() < Level.WARNING.intValue()) return;
        if (!captureWarnings && level.intValue() < Level.SEVERE.intValue()) return;

        reentrant.set(Boolean.TRUE);
        try {
            String message = record.getMessage() == null ? "" : record.getMessage();
            Throwable thrown = record.getThrown();
            String source = record.getLoggerName() == null ? "server" : record.getLoggerName();
            String stack = thrown == null ? "" : stackOf(thrown);

            // The fold key is built from the shape of the failure, not its text.
            String signature = level.getName() + '|' + source + '|' + normalise(message)
                    + '|' + (thrown == null ? "" : thrown.getClass().getName() + firstFrame(thrown));

            long now = System.currentTimeMillis();
            String label = thrown == null ? message
                    : (message.isEmpty() ? thrown.toString() : message + " — " + thrown);

            issues.compute(signature, (ignored, existing) -> existing == null
                    ? new Issue(signature, level.getName(), source, label, stack, now, now, 1)
                    : existing.seenAgain(now));

            trim();
            version.incrementAndGet();
        } catch (Exception ignored) {
            // A logging handler that throws takes the log with it. There is nothing useful to
            // do here and nowhere safe to report it to.
        } finally {
            reentrant.set(Boolean.FALSE);
        }
    }

    /** Digits and hex blanked, so the same fault about different entities folds together. */
    private static String normalise(String message) {
        return message.toLowerCase(Locale.ROOT)
                .replaceAll("0x[0-9a-f]+", "#")
                .replaceAll("\\d+", "#")
                .trim();
    }

    private static String firstFrame(Throwable thrown) {
        StackTraceElement[] frames = thrown.getStackTrace();
        return frames.length == 0 ? "" : "@" + frames[0];
    }

    private static String stackOf(Throwable thrown) {
        StringWriter writer = new StringWriter();
        thrown.printStackTrace(new PrintWriter(writer));

        String[] lines = writer.toString().split("\r?\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, MAX_STACK_LINES); i++) {
            out.append(lines[i]).append('\n');
        }
        if (lines.length > MAX_STACK_LINES) {
            out.append("\t... ").append(lines.length - MAX_STACK_LINES).append(" righe in meno\n");
        }
        return out.toString();
    }

    /** Drops the least recently seen faults once the map is over capacity. */
    private void trim() {
        if (issues.size() <= capacity) return;
        List<Issue> ordered = new ArrayList<>(issues.values());
        ordered.sort(Comparator.comparingLong(Issue::lastSeen));
        for (int i = 0; i < ordered.size() - capacity; i++) {
            issues.remove(ordered.get(i).key());
        }
    }

    // ------------------------------------------------------------------ reading

    /** Bumped on every capture; the event stream uses it to skip an unchanged payload. */
    public long version() {
        return version.get();
    }

    public int count() {
        return issues.size();
    }

    /** How many distinct faults are at SEVERE — the number worth putting on a badge. */
    public long severeCount() {
        return issues.values().stream().filter(issue -> "SEVERE".equals(issue.level())).count();
    }

    /** Newest first. */
    public List<Issue> recent() {
        List<Issue> ordered = new ArrayList<>(issues.values());
        ordered.sort(Comparator.comparingLong(Issue::lastSeen).reversed());
        return ordered;
    }

    public void clear() {
        issues.clear();
        version.incrementAndGet();
    }

    public String json() {
        return Json.array(recent(), (writer, issue) -> writer.obj(json -> json
                .str("key", issue.key())
                .str("level", issue.level())
                .str("source", issue.source())
                .str("message", issue.message())
                .str("stack", issue.stack())
                .num("first", issue.firstSeen())
                .num("last", issue.lastSeen())
                .num("count", issue.count())));
    }
}
