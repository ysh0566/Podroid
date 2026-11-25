package com.excp.podroid.hostservices;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carries SOCKS5 UDP datagrams over a framed guest TCP stream. The upstream UDP
 * socket stays on Android, so loopback SOCKS endpoints and SLIRP both work.
 * Each association owns its upstream SOCKS control connection; closing either
 * TCP side revokes the UDP socket. No direct-to-destination UDP fallback exists.
 */
public final class UdpRelay implements Closeable {
    static final int MAX_PACKET = 65507;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final InetAddress address;
    private final int prefix;
    private final InetSocketAddress upstream;
    private final RelayBudget budget;
    private final Set<Session> sessions = new HashSet<>();
    private final ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET + 1);
    private final AtomicLong bytes = new AtomicLong(), rejected = new AtomicLong();
    private final Thread worker;
    private volatile int active;
    private volatile boolean closed;
    private volatile String failure = "";

    UdpRelay(InetAddress address, int prefix, int port, InetSocketAddress upstream, RelayBudget budget) throws IOException {
        this.address = address;
        this.prefix = prefix;
        this.upstream = upstream;
        this.budget = budget;
        selector = Selector.open();
        ServerSocketChannel opened = null;
        try {
            opened = ServerSocketChannel.open();
            // QEMU reuses Android loopback on every boot. Old framed TCP
            // sessions in TIME_WAIT must not prevent the next VM from binding.
            opened.socket().setReuseAddress(true);
            opened.configureBlocking(false);
            opened.bind(new InetSocketAddress(address, port), Math.min(128, budget.limit));
            opened.register(selector, SelectionKey.OP_ACCEPT);
            listener = opened;
        } catch (IOException | RuntimeException e) {
            closeQuietly(opened); closeQuietly(selector); throw e;
        }
        worker = new Thread(this::run, "podroid-udp-selector");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        try {
            while (!closed) {
                selector.select(100);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext() && !closed) {
                    SelectionKey key = it.next(); it.remove();
                    if (!key.isValid()) continue;
                    if (key.channel() == listener) { accept(); continue; }
                    Session s = (Session) key.attachment();
                    try {
                        if (key.channel() == s.control) control(s, key);
                        else if (key.channel() == s.client) {
                            if (key.isWritable()) {
                                s.out.flip(); s.client.write(s.out); s.out.compact();
                            }
                            if (key.isReadable()) {
                                if (s.client.read(s.in) < 0) { closeSession(s); continue; }
                                sendPackets(s);
                            }
                        } else {
                            if (key.isWritable()) sendPackets(s);
                            if (key.isReadable()) receivePacket(s);
                        }
                        refresh(s);
                    } catch (IOException | RuntimeException e) { closeSession(s); }
                }
                long now = System.nanoTime();
                for (Session s : sessions.toArray(new Session[0])) {
                    long timeout = TimeUnit.SECONDS.toNanos(s.stage == 3 ? 180 : 5);
                    if (now - s.lastActivity > timeout) closeSession(s);
                }
            }
        } catch (Exception e) { if (!closed) failure = e.getClass().getSimpleName(); }
        finally {
            for (Session s : sessions.toArray(new Session[0])) closeSession(s);
            closeQuietly(listener); closeQuietly(selector);
        }
    }

    private void accept() throws IOException {
        for (int i = 0; i < 32 && !closed; i++) {
            SocketChannel client = listener.accept();
            if (client == null) return;
            if (!TcpRelay.sameSubnet(address, client.socket().getInetAddress(), prefix) || !budget.acquire()) {
                rejected.incrementAndGet(); closeQuietly(client); continue;
            }
            Session s = new Session(client);
            sessions.add(s); active = sessions.size();
            try {
                client.configureBlocking(false);
                client.socket().setTcpNoDelay(true);
                // Read EOF while negotiating too; a vanished guest must release its slot.
                s.clientKey = client.register(selector, SelectionKey.OP_READ, s);
                s.control = SocketChannel.open(); s.control.configureBlocking(false);
                s.connected = s.control.connect(upstream);
                s.controlKey = s.control.register(selector,
                    s.connected ? SelectionKey.OP_WRITE : SelectionKey.OP_CONNECT, s);
            } catch (IOException | RuntimeException e) { closeSession(s); }
        }
    }

    private void control(Session s, SelectionKey key) throws IOException {
        if (key.isConnectable()) s.connected = s.control.finishConnect();
        if (!s.connected) return;
        if (key.isWritable() && s.controlOut.hasRemaining()) s.control.write(s.controlOut);
        if (!key.isReadable()) return;
        int n = s.control.read(s.controlIn);
        if (n < 0) throw new IOException("SOCKS control closed");
        if (s.stage == 3 && n > 0) throw new IOException("Unexpected SOCKS control data");
        if (s.stage == 0 && s.controlIn.position() >= 2) {
            if (s.controlIn.get(0) != 5 || s.controlIn.get(1) != 0 || s.controlIn.position() != 2)
                throw new IOException("SOCKS5 no-auth required");
            s.controlIn.clear();
            // The upstream chooses its UDP relay endpoint. Never trust an endpoint
            // supplied by the guest (which would turn this into arbitrary UDP egress).
            s.controlOut = ByteBuffer.wrap(new byte[]{5,3,0,1,0,0,0,0,0,0});
            s.stage = 1;
        } else if (s.stage == 1 && s.controlIn.position() >= 4) {
            int type = s.controlIn.get(3) & 255;
            int size = type == 1 ? 10 : type == 4 ? 22 : -1;
            if (size < 0 || s.controlIn.get(0) != 5 || s.controlIn.get(1) != 0 || s.controlIn.get(2) != 0)
                throw new IOException("UDP association unavailable");
            if (s.controlIn.position() < size) return;
            if (s.controlIn.position() != size) throw new IOException("Invalid SOCKS reply");
            byte[] ip = new byte[size - 6];
            s.controlIn.flip(); s.controlIn.position(4); s.controlIn.get(ip);
            int port = s.controlIn.getShort() & 65535;
            InetAddress remote = InetAddress.getByAddress(ip);
            if (remote.isAnyLocalAddress()) remote = upstream.getAddress();
            // This feature targets the configured Android proxy, not third-party
            // UDP relays advertised at unrelated addresses.
            if (port == 0 || !remote.equals(upstream.getAddress())) throw new IOException("Unexpected UDP endpoint");
            s.udp = DatagramChannel.open(); s.udp.configureBlocking(false);
            s.udp.connect(new InetSocketAddress(remote, port));
            s.udpKey = s.udp.register(selector, SelectionKey.OP_READ, s);
            s.controlIn.clear(); s.stage = 3;
            s.out.put((byte) 1); // Ready, followed by uint16-length SOCKS UDP packets.
        }
    }

    private void sendPackets(Session s) throws IOException {
        if (s.stage != 3) {
            if (s.in.position() != 0) throw new IOException("Data before association");
            return;
        }
        s.blocked = false;
        s.in.flip();
        while (s.in.remaining() >= 2) {
            int start = s.in.position(), size = s.in.getShort() & 65535;
            if (size < 7 || size > MAX_PACKET) throw new IOException("Invalid datagram length");
            if (s.in.remaining() < size) { s.in.position(start); break; }
            ByteBuffer data = s.in.slice(); data.limit(size);
            if (!validPacket(data)) throw new IOException("Invalid SOCKS UDP packet");
            int written = s.udp.write(data);
            if (written == 0) { s.in.position(start); s.blocked = true; break; }
            if (written != size) throw new IOException("Partial UDP write");
            bytes.addAndGet(size); s.lastActivity = System.nanoTime();
            s.in.position(start + 2 + size);
        }
        s.in.compact();
    }

    private void receivePacket(Session s) throws IOException {
        packet.clear();
        int size = s.udp.read(packet);
        packet.flip();
        if (size < 7 || size > MAX_PACKET || !validPacket(packet)) return;
        // UDP cannot apply remote backpressure. Drop a whole datagram when the
        // bounded guest stream buffer is full; never grow an unbounded queue.
        if (s.out.remaining() < size + 2) return;
        s.out.putShort((short) size).put(packet);
        bytes.addAndGet(size); s.lastActivity = System.nanoTime();
    }

    static boolean validPacket(ByteBuffer data) {
        int n = data.remaining(), p = data.position();
        if (n < 7 || data.get(p) != 0 || data.get(p+1) != 0 || data.get(p+2) != 0) return false;
        int type = data.get(p+3) & 255;
        int header = type == 1 ? 10 : type == 4 ? 22 : type == 3 ? 7 + (data.get(p+4) & 255) : Integer.MAX_VALUE;
        return n >= header && !(type == 3 && data.get(p+4) == 0);
    }

    private void refresh(Session s) {
        s.clientKey.interestOps((!s.blocked && s.in.hasRemaining() ? SelectionKey.OP_READ : 0) |
            (s.out.position() > 0 ? SelectionKey.OP_WRITE : 0));
        s.controlKey.interestOps(!s.connected ? SelectionKey.OP_CONNECT :
            s.controlOut.hasRemaining() ? SelectionKey.OP_WRITE : SelectionKey.OP_READ);
        if (s.udpKey != null) s.udpKey.interestOps(SelectionKey.OP_READ | (s.blocked ? SelectionKey.OP_WRITE : 0));
    }

    private void closeSession(Session s) {
        if (s.clientKey != null) s.clientKey.cancel();
        if (s.controlKey != null) s.controlKey.cancel();
        if (s.udpKey != null) s.udpKey.cancel();
        closeQuietly(s.client); closeQuietly(s.control); closeQuietly(s.udp);
        if (sessions.remove(s)) budget.release();
        active = sessions.size();
    }
    public int port() { return listener.socket().getLocalPort(); }
    public int connections() { return active; }
    public long bytes() { return bytes.get(); }
    public long rejectedConnections() { return rejected.get(); }
    public String failure() { return failure; }
    public void close() {
        closed = true; selector.wakeup();
        try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static void closeQuietly(Closeable c) { if (c != null) try { c.close(); } catch (IOException ignored) {} }
    private static final class Session {
        final SocketChannel client;
        SocketChannel control;
        DatagramChannel udp;
        SelectionKey clientKey, controlKey, udpKey;
        final ByteBuffer in = ByteBuffer.allocate(MAX_PACKET + 2), out = ByteBuffer.allocate(MAX_PACKET + 3);
        final ByteBuffer controlIn = ByteBuffer.allocate(262);
        ByteBuffer controlOut = ByteBuffer.wrap(new byte[]{5,1,0});
        int stage;
        boolean connected, blocked;
        long lastActivity = System.nanoTime();
        Session(SocketChannel client) { this.client = client; }
    }
}
