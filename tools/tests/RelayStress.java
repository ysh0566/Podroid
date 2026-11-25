package com.excp.podroid.hostservices;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Standalone JVM/Android harness: loopback only, synthetic data, no VM or external proxy. */
public final class RelayStress {
    static final InetAddress LOCAL;
    static int peakFds = -1;
    static int fds() {
        String[] entries = new java.io.File("/proc/self/fd").list();
        return entries == null ? -1 : entries.length;
    }
    static { try { LOCAL = InetAddress.getByName("127.0.0.1"); } catch (Exception e) { throw new RuntimeException(e); } }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static long threads() {
        long count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive() && thread.getName().equals("podroid-proxy-selector")) count++;
        return count;
    }
    static void waitCount(TcpRelay relay, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (relay.connections() != count && System.nanoTime() < end) Thread.sleep(10);
        require(relay.connections() == count, "active slots: " + relay.connections() + " expected " + count);
    }
    public static void main(String[] args) throws Exception {
        int limit = args.length == 0 ? TcpRelay.CONNECTION_LIMIT : Integer.parseInt(args[0]);
        int rounds = args.length < 2 ? 8 : Integer.parseInt(args[1]);
        int payloadBytes = args.length < 3 ? 262144 : Integer.parseInt(args[2]);
        long baselineThreads = threads();
        long started = System.nanoTime();
        long bytes;
        int peak;
        long rejected;
        int idleFds = -1;
        try (Echo upstream = new Echo(); TcpRelay relay = new TcpRelay(LOCAL, 32, 0,
                new InetSocketAddress(LOCAL, upstream.port()), limit)) {
            for (int round = 0; round < rounds; round++) {
                transfer(relay, limit, payloadBytes, round == 0);
                waitCount(relay, 0);
                int currentFds = fds();
                if (round == 0) idleFds = currentFds;
                require(currentFds < 0 || currentFds <= idleFds + 4, "file descriptors leaked across rounds");
                require(relay.failure().isEmpty(), "selector failed: " + relay.failure());
                require(threads() == baselineThreads + 1, "thread count grew with connections");
            }
            // An abandoned, backpressured client must not stall unrelated connections.
            try (SocketChannel slow = SocketChannel.open(new InetSocketAddress(LOCAL, relay.port()))) {
                slow.socket().setReceiveBufferSize(1024);
                slow.configureBlocking(false);
                byte[] block = new byte[1048576];
                Arrays.fill(block, (byte) 7);
                ByteBuffer pending = ByteBuffer.wrap(block);
                while (pending.hasRemaining() && slow.write(pending) > 0) { /* Fill until backpressured. */ }
                transfer(relay, Math.min(8, limit - 1), 65536, false);
                slow.socket().setSoLinger(true, 0); // Exercise abrupt RST as well as orderly EOF.
            }
            waitCount(relay, 0);
            // Repeated refusal upstream must release slots and leave the listener healthy.
            bytes = relay.bytes(); peak = relay.peakConnections(); rejected = relay.rejectedConnections();
            require(rejected == 32, "rejection counter: " + rejected);
            require(peak == limit, "peak capacity was not reached");
        }
        require(threads() == baselineThreads, "selector thread leaked after close");
        upstreamFailure();
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(Locale.ROOT,
            "PASS limit=%d rounds=%d peak=%d rejected=%d bytes=%d seconds=%.3f MiB_per_s=%.2f selector_threads=1 buffer_bound_MiB=%.2f peak_fds=%d idle_fds=%d%n",
            limit, rounds, peak, rejected, bytes, seconds, bytes / 1048576.0 / seconds,
            limit * 2.0 * TcpRelay.BUFFER_BYTES / 1048576, peakFds, idleFds);
    }

    static void transfer(TcpRelay relay, int count, int total, boolean overflow) throws Exception {
        List<SocketChannel> channels = new ArrayList<>();
        try (Selector selector = Selector.open()) {
            for (int i = 0; i < count; i++) {
                SocketChannel socket = SocketChannel.open(); channels.add(socket);
                socket.socket().connect(new InetSocketAddress(LOCAL, relay.port()), 3000);
                socket.configureBlocking(false);
                socket.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ, new Traffic(i, total));
            }
            peakFds = Math.max(peakFds, fds());
            if (overflow) {
                waitCount(relay, count);
                for (int i = 0; i < 32; i++) try (Socket excess = new Socket(LOCAL, relay.port())) {
                    excess.setSoTimeout(3000);
                    require(excess.getInputStream().read() == -1, "overflow admitted");
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(40, total / 1048576 * 2));
            int done = 0;
            while (done < count) {
                require(System.nanoTime() < deadline, "traffic timed out");
                selector.select(100);
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next(); keys.remove();
                    Traffic t = (Traffic) key.attachment();
                    SocketChannel socket = (SocketChannel) key.channel();
                    if (key.isWritable()) {
                        if (!t.out.hasRemaining()) t.fill();
                        t.sent += socket.write(t.out);
                        if (t.sent == total) {
                            socket.socket().shutdownOutput();
                            key.interestOps(SelectionKey.OP_READ);
                        }
                    }
                    if (key.isReadable()) {
                        t.in.clear(); int n = socket.read(t.in);
                        if (n < 0) {
                            require(t.received == total, "truncated response " + t.received);
                            key.cancel(); socket.close(); done++;
                        } else {
                            t.in.flip();
                            while (t.in.hasRemaining()) {
                                require(t.in.get() == t.pattern(t.received++), "corrupt payload");
                            }
                        }
                    }
                }
            }
        } finally { for (SocketChannel channel : channels) channel.close(); }
    }

    static void upstreamFailure() throws Exception {
        int port;
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0, 1, LOCAL)) { port = unused.getLocalPort(); }
        try (TcpRelay relay = new TcpRelay(LOCAL, 32, 0, new InetSocketAddress(LOCAL, port), 2)) {
            for (int i = 0; i < 20; i++) try (Socket socket = new Socket(LOCAL, relay.port())) {
                socket.setSoTimeout(3000);
                require(socket.getInputStream().read() == -1, "refusal did not close client");
            }
            waitCount(relay, 0);
            require(relay.connectFailures() == 20, "missing connect failures");
            require(relay.failure().isEmpty(), "upstream refusal killed listener");
        }
    }

    static final class Traffic {
        final int id, total;
        int sent, received;
        final ByteBuffer in = ByteBuffer.allocate(8192), out = ByteBuffer.allocate(8192);
        Traffic(int id, int total) { this.id = id; this.total = total; out.limit(0); }
        byte pattern(int offset) { return (byte) ((offset + id * 17) % 251); }
        void fill() {
            out.clear();
            int size = Math.min(out.capacity(), total - sent);
            for (int i = 0; i < size; i++) out.put(pattern(sent + i));
            out.flip();
        }
    }

    /** Small single-socket echo peer, independent of the relay's two-ended state machine. */
    static final class Echo implements Closeable {
        final Selector selector = Selector.open();
        final ServerSocketChannel listener = ServerSocketChannel.open();
        final Thread thread;
        volatile boolean stopped;
        Echo() throws Exception {
            listener.configureBlocking(false); listener.socket().bind(new InetSocketAddress(LOCAL, 0), 128);
            listener.register(selector, SelectionKey.OP_ACCEPT);
            thread = new Thread(this::run, "stress-echo"); thread.start();
        }
        int port() { return listener.socket().getLocalPort(); }
        void run() {
            try {
                while (!stopped) {
                    selector.select(100);
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next(); keys.remove();
                        if (!key.isValid()) continue;
                        if (key.isAcceptable()) {
                            SocketChannel socket = listener.accept();
                            if (socket != null) { socket.configureBlocking(false); socket.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(8192)); }
                            continue;
                        }
                        SocketChannel socket = (SocketChannel) key.channel();
                        ByteBuffer b = (ByteBuffer) key.attachment();
                        try {
                            if (key.isReadable()) {
                                int n = socket.read(b);
                                if (n < 0) { socket.close(); continue; }
                            }
                            b.flip(); socket.write(b); b.compact();
                            // Drain each block before the next read, including the final EOF.
                            key.interestOps(b.position() == 0 ? SelectionKey.OP_READ : SelectionKey.OP_WRITE);
                        } catch (IOException ignored) { socket.close(); }
                    }
                }
            } catch (IOException e) { if (!stopped) throw new RuntimeException(e); }
            finally {
                for (SelectionKey key : selector.keys()) try { key.channel().close(); } catch (IOException ignored) { }
                try { selector.close(); } catch (IOException ignored) { }
            }
        }
        public void close() {
            stopped = true; selector.wakeup();
            try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
