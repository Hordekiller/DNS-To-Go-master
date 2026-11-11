package com.hololo.app.dnschanger.dns;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One running tunnel: a reader thread, a writer thread and a bounded resolver pool,
 * all owned by this object. Nothing here touches service-level fields, so a session
 * that is still shutting down can never close or disable a newer session.
 *
 * <p>Packet ownership: the reader owns a single read buffer; each DNS payload is
 * copied into a fresh array before it is handed to a worker, so no buffer is ever
 * shared between threads (replaces the old index-based PacketPool, which could hand
 * out a buffer still in use and leaked permits on executor rejection).
 *
 * <p>Every intercepted UDP/53 query gets exactly one reply (answer, NXDOMAIN or
 * SERVFAIL), including under overload. TCP segments get a RST so DNS-over-TCP
 * fails fast instead of hanging.
 */
public final class TunnelSession {

    public interface PacketSource {
        /** Blocks until a packet is read. Returns its length, or -1 at end of stream. */
        int read(byte[] buffer) throws IOException;

        /** Unblocks {@link #read} and releases the underlying descriptor. Idempotent. */
        void close();
    }

    public interface PacketSink {
        void write(byte[] packet, int length) throws IOException;
    }

    public interface UpstreamProvider {
        List<DnsQueryProcessor.Upstream> upstreams();
    }

    public interface TerminationListener {
        /** Called once, from the reader thread, when the session ends for any reason. */
        void onTerminated(TunnelSession session, boolean requested, Throwable cause);
    }

    public enum State { NEW, RUNNING, STOPPING, STOPPED }

    public static final class Config {
        public int workerThreads = 32;
        public int workQueueCapacity = 256;
        public int writeQueueCapacity = 1024;
        public int readBufferSize = 32767;

        public static Config defaults() {
            return new Config();
        }
    }

    /** Counters exposed for diagnostics and tests. */
    public static final class Stats {
        public final AtomicLong packetsRead = new AtomicLong();
        public final AtomicLong dnsQueries = new AtomicLong();
        public final AtomicLong repliesWritten = new AtomicLong();
        public final AtomicLong overloadServfail = new AtomicLong();
        public final AtomicLong writeQueueDrops = new AtomicLong();
        public final AtomicLong tcpResets = new AtomicLong();
        public final AtomicLong ignored = new AtomicLong();
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final String name;
    private final PacketSource source;
    private final PacketSink sink;
    private final DnsQueryProcessor processor;
    private final UpstreamProvider upstreamProvider;
    private final Config config;
    private final TerminationListener terminationListener;
    private final Stats stats = new Stats();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final BlockingQueue<byte[]> writeQueue;
    private final ThreadPoolExecutor workers;
    private Thread readerThread;
    private Thread writerThread;

    public TunnelSession(PacketSource source, PacketSink sink, DnsQueryProcessor processor,
                         UpstreamProvider upstreamProvider, Config config,
                         TerminationListener terminationListener) {
        this.name = "tun-" + SEQ.incrementAndGet();
        this.source = source;
        this.sink = sink;
        this.processor = processor;
        this.upstreamProvider = upstreamProvider;
        this.config = config != null ? config : Config.defaults();
        this.terminationListener = terminationListener;
        this.writeQueue = new ArrayBlockingQueue<>(this.config.writeQueueCapacity);
        this.workers = new ThreadPoolExecutor(
                this.config.workerThreads, this.config.workerThreads,
                30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(this.config.workQueueCapacity),
                r -> {
                    Thread t = new Thread(r, name + "-resolver");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
    }

    public String name() { return name; }
    public State state() { return state.get(); }
    public Stats stats() { return stats; }
    public boolean isRunning() { return state.get() == State.RUNNING; }

    public void start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("Session " + name + " already started: " + state.get());
        }
        writerThread = new Thread(this::writeLoop, name + "-writer");
        writerThread.setDaemon(true);
        readerThread = new Thread(this::readLoop, name + "-reader");
        readerThread.setDaemon(true);
        writerThread.start();
        readerThread.start();
    }

    /** Requests shutdown. Non-blocking and idempotent; safe from any thread. */
    public void stop() {
        if (!stopRequested.compareAndSet(false, true)) return;
        state.compareAndSet(State.NEW, State.STOPPED);
        state.compareAndSet(State.RUNNING, State.STOPPING);
        source.close();
        Thread r = readerThread;
        if (r != null) r.interrupt();
        Thread w = writerThread;
        if (w != null) w.interrupt();
        workers.shutdownNow();
    }

    /** Waits for the reader and writer to exit. Returns true if both did. */
    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Thread t : new Thread[]{readerThread, writerThread}) {
            if (t == null) continue;
            long left = deadline - System.currentTimeMillis();
            if (left > 0) t.join(left);
            if (t.isAlive()) return false;
        }
        return workers.awaitTermination(Math.max(0, deadline - System.currentTimeMillis()),
                TimeUnit.MILLISECONDS);
    }

    private void readLoop() {
        byte[] buffer = new byte[config.readBufferSize];
        Throwable cause = null;
        try {
            while (!stopRequested.get() && !Thread.currentThread().isInterrupted()) {
                int length = source.read(buffer);
                if (length < 0) break;
                if (length == 0) continue;
                stats.packetsRead.incrementAndGet();
                dispatch(buffer, length);
            }
        } catch (IOException e) {
            if (!stopRequested.get()) cause = e;
        } catch (RuntimeException e) {
            cause = e;
        } finally {
            boolean requested = stopRequested.get();
            stop();
            state.set(State.STOPPED);
            if (terminationListener != null) {
                try {
                    terminationListener.onTerminated(this, requested, cause);
                } catch (RuntimeException ignored) {
                    // listener bugs must not mask shutdown
                }
            }
        }
    }

    /** Runs on the reader thread. Must never block on the network. */
    void dispatch(byte[] buffer, int length) {
        IpPacket packet = IpPacket.parse(buffer, length);
        if (packet == null) {
            stats.ignored.incrementAndGet();
            return;
        }
        if (packet.protocol == IpPacket.PROTO_TCP) {
            byte[] rst = IpPacket.buildTcpReset(packet);
            if (rst != null) {
                stats.tcpResets.incrementAndGet();
                enqueueWrite(rst);
            }
            return;
        }
        if (packet.protocol != IpPacket.PROTO_UDP || packet.dstPort != 53) {
            stats.ignored.incrementAndGet();
            return;
        }
        stats.dnsQueries.incrementAndGet();
        final byte[] query = packet.copyPayload(buffer);
        try {
            workers.execute(() -> resolveAndReply(packet, query));
        } catch (RejectedExecutionException e) {
            if (stopRequested.get()) return;
            // Overloaded: answer immediately instead of letting the client time out.
            stats.overloadServfail.incrementAndGet();
            byte[] servfail = DnsMessage.buildErrorResponse(query, DnsMessage.RCODE_SERVFAIL);
            if (servfail != null) enqueueWrite(IpPacket.buildUdpReply(packet, servfail));
        }
    }

    private void resolveAndReply(IpPacket packet, byte[] query) {
        if (stopRequested.get()) return;
        byte[] response;
        try {
            response = processor.process(query, upstreamProvider.upstreams());
        } catch (RuntimeException e) {
            response = DnsMessage.buildErrorResponse(query, DnsMessage.RCODE_SERVFAIL);
        }
        if (response != null && !stopRequested.get()) {
            enqueueWrite(IpPacket.buildUdpReply(packet, response));
        }
    }

    private void enqueueWrite(byte[] packet) {
        if (!writeQueue.offer(packet)) stats.writeQueueDrops.incrementAndGet();
    }

    private void writeLoop() {
        try {
            while (!stopRequested.get()) {
                byte[] packet = writeQueue.poll(500, TimeUnit.MILLISECONDS);
                if (packet == null) continue;
                try {
                    sink.write(packet, packet.length);
                    stats.repliesWritten.incrementAndGet();
                } catch (IOException e) {
                    if (stopRequested.get()) return;
                    // A single failed write (e.g. EINVAL for one packet) is not fatal;
                    // a dead descriptor makes the reader exit and stop the session.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
