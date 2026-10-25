package dev.lorenzzz.lobbycore.util;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A very small JSON writer.
 *
 * <p>No Jackson, no Gson: the panel emits a handful of known shapes and one dependency for that
 * is a dependency to shade, relocate and keep current. What it does need is correct escaping —
 * the config editor sends whole YAML files through this, and a file holding a quote, a backslash
 * or a colour code would otherwise produce a payload the browser silently refuses to parse.
 *
 * <p>Usage is builder-ish and deliberately blunt:
 * <pre>
 * Json.object(o -&gt; {
 *     o.str("name", player.getName());
 *     o.num("kills", 4);
 *     o.arr("tags", a -&gt; tags.forEach(a::str));
 * });
 * </pre>
 */
public final class Json {

    private final StringBuilder out;
    private boolean first = true;

    private Json(StringBuilder out) {
        this.out = out;
    }

    // ------------------------------------------------------------- entry points

    /** Builds a JSON object and returns it as a string. */
    public static String object(Consumer<Json> body) {
        StringBuilder out = new StringBuilder(256);
        writeObject(out, body);
        return out.toString();
    }

    /** Builds a JSON array of arbitrary elements. */
    public static <T> String array(Collection<T> values, BiConsumer<Json, T> element) {
        StringBuilder out = new StringBuilder(256);
        writeArray(out, values, element);
        return out.toString();
    }

    // ------------------------------------------------------------ object fields

    public Json str(String key, String value) {
        key(key);
        string(out, value);
        return this;
    }

    public Json num(String key, long value) {
        key(key);
        out.append(value);
        return this;
    }

    public Json num(String key, double value) {
        key(key);
        // Round to two decimals: every number the panel shows is a TPS, a percentage or a ratio,
        // and a raw double prints seventeen digits of noise.
        out.append(Math.round(value * 100.0) / 100.0);
        return this;
    }

    public Json bool(String key, boolean value) {
        key(key);
        out.append(value);
        return this;
    }

    /** A field whose value is already-built JSON — an object, an array, or {@code null}. */
    public Json raw(String key, String json) {
        key(key);
        out.append(json == null || json.isEmpty() ? "null" : json);
        return this;
    }

    public Json obj(String key, Consumer<Json> body) {
        key(key);
        writeObject(out, body);
        return this;
    }

    public <T> Json arr(String key, Collection<T> values, BiConsumer<Json, T> element) {
        key(key);
        writeArray(out, values, element);
        return this;
    }

    // ------------------------------------------------------------ array elements

    /** Appends a bare string element. Only valid while building an array. */
    public void str(String value) {
        comma();
        string(out, value);
    }

    /** Appends an object element. Only valid while building an array. */
    public void obj(Consumer<Json> body) {
        comma();
        writeObject(out, body);
    }

    public void num(long value) {
        comma();
        out.append(value);
    }

    // ------------------------------------------------------------------ internals

    private void key(String name) {
        comma();
        string(out, name);
        out.append(':');
    }

    private void comma() {
        if (!first) out.append(',');
        first = false;
    }

    private static void writeObject(StringBuilder out, Consumer<Json> body) {
        out.append('{');
        body.accept(new Json(out));
        out.append('}');
    }

    private static <T> void writeArray(StringBuilder out, Collection<T> values, BiConsumer<Json, T> element) {
        out.append('[');
        // Separators are the element methods' job — str(v), obj(b) and num(v) each emit their
        // own leading comma from the second element on. Adding one here as well is what produced
        // `[,"a",,"b"]`, which no parser accepts.
        Json writer = new Json(out);
        for (T value : values) {
            element.accept(writer, value);
        }
        out.append(']');
    }

    /**
     * A JSON string literal.
     *
     * <p>Everything below 0x20 is escaped, not just the named few: a YAML file pasted into the
     * editor can carry a stray control character, and an unescaped one makes the whole response
     * unparseable with no hint as to which file caused it. {@code <} is escaped too, so a payload
     * can never close a {@code <script>} tag if it is ever inlined into a page.
     */
    public static void string(StringBuilder out, String value) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A standalone JSON string, for callers splicing one into hand-built JSON. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value == null ? 4 : value.length() + 2);
        string(out, value);
        return out.toString();
    }
}
