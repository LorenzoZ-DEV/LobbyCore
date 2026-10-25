package dev.lorenzzz.lobbycore.module.modules.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One live panel event stream, with a writer thread of its own.
 *
 * <p>An SSE connection lives for as long as the tab is open, so it cannot be driven by scheduler
 * tasks — those are for work that finishes. And a client that stops reading (a closed laptop,
 * dead Wi-Fi, a proxy that buffers) fills its socket buffer, at which point the write blocks
 * forever, because {@code com.sun.net.httpserver} has no write timeout. That thread holds the
 * monitor of the response's buffered stream, so every later broadcast blocks on the same lock,
 * each one arriving on a fresh thread. One idle tab is enough to walk a server into its process
 * limit.
 *
 * <p>So each client owns exactly one thread and a bounded queue. Broadcasters only ever call
 * {@link #send}, which offers and returns and never touches the socket. A client that cannot keep
 * up overflows its queue and is dropped rather than holding anyone up — the browser's
 * {@code EventSource} reconnects on its own. The worst case is one stuck thread per connection,
 * which is why {@link WebServer} caps how many may be open.
 */
final class SseClient {

    /** Roughly thirty seconds of snapshots. Past that the client is not reading. */
    private static final int QUEUE_CAPACITY = 32;

    private final HttpExchange exchange;
    private final OutputStream out;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Runs when the writer thread has genuinely ended — the only honest moment to free a slot. */
    private final Consumer<SseClient> onExit;
    private final Thread writer;

    SseClient(HttpExchange exchange, Consumer<SseClient> onExit) {
        this.exchange = exchange;
        this.out = exchange.getResponseBody();
        this.onExit = onExit;
        this.writer = Thread.ofPlatform()
                .name("lobbycore-sse-" + remote(exchange))
                .daemon(true)
                .unstarted(this::pump);
    }

    /** Separate from the constructor so the writer never sees a half-built client. */
    void start() {
        writer.start();
    }

    /**
     * Queues a frame. Never blocks, never throws: a full queue means the client is not draining,
     * so it is dropped here instead of stalling the broadcast for everybody else.
     */
    void send(byte[] frame) {
        if (closed.get()) return;
        if (!queue.offer(frame)) close();
    }

    boolean isClosed() {
        return closed.get();
    }

    private void pump() {
        try {
            while (!closed.get()) {
                byte[] frame = queue.take();
                out.write(frame);
                out.flush();
            }
        } catch (IOException | RuntimeException disconnected) {
            // The tab went away, or the connection was closed under us.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            closed.set(true);
            // Only this thread may touch the response: closing an exchange flushes and finishes
            // the chunked stream, which would block the caller on exactly the lock we are trying
            // to stay away from. From here it is safe — nothing else is writing.
            try {
                exchange.close();
            } catch (Exception ignored) {
                // already gone
            }
            onExit.accept(this);
        }
    }

    /**
     * Marks the stream dead and wakes the writer. Safe from any thread: deliberately does no I/O
     * and takes no lock the writer might hold, so one stuck client can never spread.
     *
     * <p>This does <em>not</em> release the client's slot. Only the writer thread actually ending
     * does that, through {@code onExit} — a client wedged in a socket write has been given up on
     * but still costs a thread, and the cap has to count what exists rather than what we would
     * like to exist.
     */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (Thread.currentThread() != writer) writer.interrupt();
    }

    private static String remote(HttpExchange exchange) {
        InetSocketAddress address = exchange.getRemoteAddress();
        return address == null ? "unknown" : address.getHostString() + ":" + address.getPort();
    }
}
