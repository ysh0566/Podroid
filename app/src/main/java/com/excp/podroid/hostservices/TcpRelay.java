package com.excp.podroid.hostservices;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Binary-transparent TCP transport. Only the selector thread owns sessions and buffers. */
public final class TcpRelay implements Closeable {
    // Default preference; each listener receives its resource-capped limit explicitly.
    public static final int CONNECTION_LIMIT = 512;
    public static final int BUFFER_BYTES = 16384;
    private static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final InetAddress address;
    private final int prefix;
    private final InetSocketAddress upstream;
    private final int limit;
    private final int boundPort;
    private final Thread worker;
    private final Set<Session> sessions = new HashSet<>();
    private final AtomicLong transferred = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong connectFailures = new AtomicLong();
    private volatile int active;
    private volatile int peak;
    private volatile boolean closed;
    private volatile String failure = "";

    public TcpRelay(InetAddress address, int prefix, int port, InetSocketAddress upstream, int limit) throws IOException {
        if (address.getAddress().length != 4 || prefix < 1 || prefix > 32 || limit < 1 || upstream.isUnresolved())
            throw new IllegalArgumentException("Resolved IPv4 binding, upstream and positive limit required");
        this.address = address;
        this.prefix = prefix;
        this.upstream = upstream;
        this.limit = limit;
        selector = Selector.open();
        ServerSocketChannel opened = null;
        try {
            opened = ServerSocketChannel.open();
            opened.configureBlocking(false);
            opened.socket().setReuseAddress(true);
            opened.socket().bind(new InetSocketAddress(address, port), Math.min(limit, 128));
            opened.register(selector, SelectionKey.OP_ACCEPT);
            listener = opened;
            boundPort = listener.socket().getLocalPort();
        } catch (IOException | RuntimeException e) {
            if (opened != null) closeQuietly(opened);
            closeQuietly(selector);
            throw e;
        }
        worker = new Thread(this::run, "podroid-proxy-selector");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        try {
            while (!closed) {
                selector.select(100);
                Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
                while (ready.hasNext() && !closed) {
                    SelectionKey key = ready.next();
                    ready.remove();
                    if (!key.isValid()) continue;
                    if (key.channel() == listener) {
                        accept();
                    } else {
                        Endpoint end = (Endpoint) key.attachment();
                        try {
                            if (key.isConnectable()) end.connected = end.channel.finishConnect();
                            if (end.connected) {
                                if (key.isWritable()) write(end);
                                if (key.isReadable()) {
                                    int n = end.channel.read(end.other.pending);
                                    if (n == -1) end.inputEof = true;
                                }
                            }
                            refresh(end.session);
                        } catch (IOException e) {
                            if (!end.connected) connectFailures.incrementAndGet();
                            closeSession(end.session);
                        }
                    }
                }
                long now = System.nanoTime();
                // A blackholed upstream must not hold a capacity slot forever.
                for (Session session : sessions.toArray(new Session[0])) {
                    if (!session.right.connected && now - session.created >= CONNECT_TIMEOUT_NANOS) {
                        connectFailures.incrementAndGet();
                        closeSession(session);
                    }
                }
            }
        } catch (Exception e) {
            if (!closed) failure = e.getClass().getSimpleName();
        } finally {
            for (Session session : sessions.toArray(new Session[0])) closeSession(session);
            closeQuietly(listener);
            closeQuietly(selector);
        }
    }

    private void accept() throws IOException {
        // Bound each accept batch so a connection flood cannot starve established transfers.
        for (int i = 0; i < 64 && !closed; i++) {
            SocketChannel client = listener.accept();
            if (client == null) return;
            if (!sameSubnet(address, client.socket().getInetAddress(), prefix) || active >= limit) {
                rejected.incrementAndGet();
                closeQuietly(client);
                continue;
            }
            SocketChannel destination = null;
            Session session = null;
            try {
                client.configureBlocking(false);
                client.socket().setTcpNoDelay(true);
                destination = SocketChannel.open();
                destination.configureBlocking(false);
                destination.socket().setTcpNoDelay(true);
                session = new Session(client, destination);
                session.right.connected = destination.connect(upstream);
                session.left.key = client.register(selector, 0, session.left);
                session.right.key = destination.register(selector, 0, session.right);
                sessions.add(session);
                active = sessions.size();
                peak = Math.max(peak, active);
                refresh(session);
            } catch (IOException e) {
                connectFailures.incrementAndGet();
                if (session != null) closeSession(session);
                closeQuietly(client);
                if (destination != null) closeQuietly(destination);
            }
        }
    }

    private void write(Endpoint end) throws IOException {
        end.pending.flip();
        int n = end.channel.write(end.pending);
        transferred.addAndGet(n);
        end.pending.compact();
    }

    private void refresh(Session session) throws IOException {
        refresh(session.left);
        refresh(session.right);
        if (session.left.inputEof && session.right.inputEof &&
                session.left.pending.position() == 0 && session.right.pending.position() == 0) {
            closeSession(session);
        }
    }

    private void refresh(Endpoint end) throws IOException {
        if (!end.connected) {
            end.key.interestOps(SelectionKey.OP_CONNECT);
            return;
        }
        // Propagate EOF only after queued bytes are written. A request half-close
        // must leave the reverse direction alive for a delayed/large response.
        if (end.other.inputEof && end.pending.position() == 0 && !end.outputEof) {
            end.channel.socket().shutdownOutput();
            end.outputEof = true;
        }
        int ops = 0;
        // Fixed buffers impose backpressure: resume reads when the peer drains.
        if (!end.inputEof && end.other.pending.hasRemaining()) ops |= SelectionKey.OP_READ;
        if (end.pending.position() != 0) ops |= SelectionKey.OP_WRITE;
        end.key.interestOps(ops);
    }

    private void closeSession(Session session) {
        if (session.left.key != null) session.left.key.cancel();
        if (session.right.key != null) session.right.key.cancel();
        closeQuietly(session.left.channel);
        closeQuietly(session.right.channel);
        sessions.remove(session);
        active = sessions.size();
    }

    static boolean sameSubnet(InetAddress a, InetAddress b, int prefix) {
        byte[] left = a.getAddress(), right = b.getAddress();
        if (left.length != 4 || right.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            int bits = Math.max(0, Math.min(8, prefix - i * 8));
            int mask = (0xff << (8 - bits)) & 0xff;
            if ((left[i] & mask) != (right[i] & mask)) return false;
        }
        return true;
    }

    public int port() { return boundPort; }
    public int connections() { return active; }
    public int limit() { return limit; }
    public int peakConnections() { return peak; }
    public long bytes() { return transferred.get(); }
    public long rejectedConnections() { return rejected.get(); }
    public long connectFailures() { return connectFailures.get(); }
    public String failure() { return failure; }

    @Override public void close() {
        closed = true;
        selector.wakeup();
        if (Thread.currentThread() != worker) {
            try { worker.join(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void closeQuietly(Closeable resource) {
        try { resource.close(); } catch (IOException ignored) { }
    }

    private static final class Session {
        final long created = System.nanoTime();
        final Endpoint left;
        final Endpoint right;
        Session(SocketChannel client, SocketChannel destination) {
            left = new Endpoint(this, client, true);
            right = new Endpoint(this, destination, false);
            left.other = right;
            right.other = left;
        }
    }

    private static final class Endpoint {
        final Session session;
        final SocketChannel channel;
        // Always in write mode between events: holds bytes destined for this endpoint.
        final ByteBuffer pending = ByteBuffer.allocate(BUFFER_BYTES);
        Endpoint other;
        SelectionKey key;
        boolean connected;
        boolean inputEof;
        boolean outputEof;
        Endpoint(Session session, SocketChannel channel, boolean connected) {
            this.session = session;
            this.channel = channel;
            this.connected = connected;
        }
    }
}
